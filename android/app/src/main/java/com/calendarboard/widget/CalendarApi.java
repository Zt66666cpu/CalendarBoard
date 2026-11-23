package com.calendarboard.widget;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

final class CalendarApi {
    private static final String BASE = "https://samnpjgnvtearyeejjtg.supabase.co";
    // Supabase publishable keys are designed for client apps. RLS protects each account's rows.
    private static final String KEY = "sb_publishable_x061oGvVUpjXv0PG1J-Kag_Voayt0Ux";

    private CalendarApi() { }

    private static JSONObject request(String path, String method, JSONObject body, String token) throws Exception {
        return request(path, method, body, token, null);
    }

    private static JSONObject request(String path, String method, JSONObject body, String token,
                                      String preferHeader) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("apikey", KEY);
            connection.setRequestProperty("Accept", "application/json");
            if (preferHeader != null && !preferHeader.isEmpty())
                connection.setRequestProperty("Prefer", preferHeader);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
                }
            }
            String text = buffer.toString(StandardCharsets.UTF_8.name());
            if (status >= 400) {
                String message = "请求失败（" + status + "）";
                try {
                    JSONObject error = new JSONObject(text);
                    message = error.optString("msg", error.optString("message", error.optString("error_description", message)));
                } catch (Exception ignored) { }
                throw new ApiException(status, codeForStatus(status), message);
            }
            if (text.startsWith("[")) return new JSONObject().put("rows", new JSONArray(text));
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (ApiException error) {
            throw error;
        } catch (java.io.IOException error) {
            String message = error.getMessage();
            throw new ApiException(0, "network", message == null || message.isEmpty() ? "网络连接失败" : message);
        } catch (org.json.JSONException error) {
            throw new ApiException(0, "http", "云端返回了无法解析的数据");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String codeForStatus(int status) {
        if (status == 401) return "auth";
        if (status == 409) return "conflict";
        return "http";
    }

    private static JSONObject requestWithRefresh(Context context, String path, String method,
                                                 JSONObject body, String token,
                                                 String preferHeader) throws Exception {
        try {
            return request(path, method, body, token, preferHeader);
        } catch (ApiException error) {
            if (error.status != 401) throw error;
            return request(path, method, body, accessToken(context, true), preferHeader);
        }
    }

    static synchronized void login(Context context, String email, String password) throws Exception {
        JSONObject body = new JSONObject().put("email", email).put("password", password);
        JSONObject response = request("/auth/v1/token?grant_type=password", "POST", body, null);
        saveTokens(context, response);
        SessionStore.saveCache(context, new JSONObject());
    }

    private static void saveTokens(Context context, JSONObject response) throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000 + response.optLong("expires_in", 3600);
        JSONObject session = new JSONObject()
                .put("access_token", response.getString("access_token"))
                .put("refresh_token", response.getString("refresh_token"))
                .put("expires_at", expiresAt);
        SessionStore.saveSession(context, session);
    }

    private static String accessToken(Context context, boolean forceRefresh) throws Exception {
        long revision = SessionStore.revision();
        JSONObject session = SessionStore.session(context);
        if (session == null) throw new IllegalStateException("请先登录。");
        if (!forceRefresh && session.optLong("expires_at") > System.currentTimeMillis() / 1000 + 60)
            return session.getString("access_token");
        JSONObject body = new JSONObject().put("refresh_token", session.getString("refresh_token"));
        JSONObject response = request("/auth/v1/token?grant_type=refresh_token", "POST", body, null);
        if (revision != SessionStore.revision()) throw new IllegalStateException("登录状态已改变。");
        saveTokens(context, response);
        return response.getString("access_token");
    }

    static synchronized JSONObject syncToday(Context context) throws Exception {
        long revision = SessionStore.revision();
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        String token = accessToken(context, false);
        JSONArray rows = new JSONArray();
        for (int offset = 0; ; offset += 1000) {
            String path = "/rest/v1/calendar_items?select=id,payload&order=id.asc&limit=1000&offset=" + offset;
            JSONObject response;
            try {
                response = request(path, "GET", null, token);
            } catch (ApiException error) {
                if (error.status != 401) throw error;
                token = accessToken(context, true);
                response = request(path, "GET", null, token);
            }
            JSONArray page = response.getJSONArray("rows");
            for (int i = 0; i < page.length(); i++) rows.put(page.getJSONObject(i));
            if (page.length() < 1000) break;
        }

        JSONObject meta = null;
        JSONArray allItems = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject payload = row.optJSONObject("payload");
            if (payload == null) continue;
            if (Items.isMeta(payload)) {
                if (meta == null) meta = payload;
                continue;
            }
            payload.put("_row_id", row.optString("id"));
            allItems.put(payload);
        }
        meta = ensureMeta(context, meta, token);

        String today = LocalDate.now().toString();
        long syncedAtMs = System.currentTimeMillis();
        String syncedAt = LocalTime.now().withSecond(0).withNano(0).toString();
        JSONObject data = buildCache(allItems, meta, today, syncedAt, syncedAtMs);
        if (revision != SessionStore.revision()) throw new IllegalStateException("登录状态已改变。");
        SessionStore.saveCache(context, data);
        return data;
    }

    private static JSONObject ensureMeta(Context context, JSONObject meta, String token) throws Exception {
        if (meta != null) return meta;
        String userId = tokenUserId(token);
        if (userId.isEmpty()) throw new ApiException(401, "auth", "无法识别当前账号，请重新登录。");
        String metaId = Items.metaId(userId);
        JSONObject defaultMeta = Items.defaultMeta();
        try {
            requestWithRefresh(context, "/rest/v1/calendar_items", "POST",
                    new JSONObject().put("id", metaId).put("payload", defaultMeta), token, null);
            return defaultMeta;
        } catch (ApiException error) {
            if (error.status != 409) throw error;
            String path = "/rest/v1/calendar_items?select=id,payload&id=eq."
                    + URLEncoder.encode(metaId, "UTF-8");
            JSONObject response = requestWithRefresh(context, path, "GET", null, token, null);
            JSONArray rows = response.optJSONArray("rows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    JSONObject payload = row == null ? null : row.optJSONObject("payload");
                    if (Items.isMeta(payload)) return payload;
                }
            }
            throw new ApiException(200, "gone", "设置行不存在");
        }
    }

    static JSONObject buildCache(JSONArray allItemsWithRowId, JSONObject meta, String today,
                                 String syncedAt, long syncedAtMs) throws Exception {
        JSONObject effectiveMeta = meta == null
                ? Items.defaultMeta()
                : new JSONObject(meta.toString());
        JSONArray allItems = allItemsWithRowId == null
                ? new JSONArray()
                : new JSONArray(allItemsWithRowId.toString());

        List<JSONObject> courses = new ArrayList<>();
        List<JSONObject> schedules = new ArrayList<>();
        List<JSONObject> todosToday = new ArrayList<>();
        List<JSONObject> todosPending = new ArrayList<>();

        for (int i = 0; i < allItems.length(); i++) {
            JSONObject item = allItems.optJSONObject(i);
            if (item == null || Items.isMeta(item)) continue;
            String date = item.optString("date");
            String type = item.optString("type");
            if ("课程".equals(type) && today.equals(date)) courses.add(item);
            if ("日程".equals(type) && today.equals(date) && !Items.isArchived(item, today)) {
                schedules.add(item);
            }
            if ("待办".equals(type) && today.equals(date) && !Items.isArchived(item, today)) {
                todosToday.add(item);
            }
            if ("待办".equals(type) && Items.isPending(item, today)) {
                todosPending.add(item);
            }
        }

        courses.sort(Items.BY_TIME);
        schedules.sort(Items.BY_TIME);
        todosToday.sort(Items.BY_TIME);
        todosPending.sort((a, b) -> {
            int dateOrder = a.optString("date").compareTo(b.optString("date"));
            return dateOrder != 0 ? dateOrder : Items.BY_TIME.compare(a, b);
        });
        List<JSONObject> widgetTodos = new ArrayList<>();
        widgetTodos.addAll(todosPending);
        widgetTodos.addAll(todosToday);

        return new JSONObject()
                .put("date", today)
                .put("synced_at", syncedAt)
                .put("synced_at_ms", syncedAtMs)
                .put("meta", effectiveMeta)
                .put("all_items", allItems)
                .put("todos_today", toArray(todosToday))
                .put("todos_pending", toArray(todosPending))
                .put("course", format("课程", courses, true))
                .put("todo", format("待办", widgetTodos, false))
                .put("schedule", format("日程", schedules, true));
    }

    private static JSONArray toArray(List<JSONObject> items) {
        JSONArray result = new JSONArray();
        for (JSONObject item : items) result.put(item);
        return result;
    }

    static synchronized void saveItem(Context context, String id, JSONObject original,
                                      String date, String type, String title,
                                      String startTime, String endTime, String place) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        JSONObject item = original == null ? new JSONObject() : new JSONObject(original.toString());
        item.remove("_row_id");
        item.remove("id");
        item.put("type", type)
                .put("title", title)
                .put("date", date)
                .put("startTime", startTime)
                .put("endTime", endTime)
                .put("time", Items.buildTime(startTime, endTime))
                .put("place", place);
        if (item.has("room")) item.put("room", place);
        if (item.has("display")) item.put("display", title);
        if ("待办".equals(type) && original == null) item.put("done", false);

        if (id == null || id.isEmpty()) {
            String token = accessToken(context, false);
            String newId = newRowId();
            try {
                requestWithRefresh(context, "/rest/v1/calendar_items", "POST",
                        new JSONObject().put("id", newId).put("payload", item), token, null);
            } catch (ApiException error) {
                if (error.status != 409) throw error;
                newId = newRowId();
                requestWithRefresh(context, "/rest/v1/calendar_items", "POST",
                        new JSONObject().put("id", newId).put("payload", item), token, null);
            }
            updateLocalItem(context, newId, item);
            return;
        }
        patchPayload(context, id, item);
    }

    static synchronized void patchPayload(Context context, String id, JSONObject payload) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("无法识别事项。");
        JSONObject item = new JSONObject(payload.toString());
        item.remove("_row_id");
        item.remove("id");
        String path = "/rest/v1/calendar_items?id=eq." + URLEncoder.encode(id, "UTF-8");
        String token = accessToken(context, false);
        JSONObject response = requestWithRefresh(context, path, "PATCH",
                new JSONObject().put("payload", item), token, "return=representation");
        JSONArray rows = response.optJSONArray("rows");
        if (rows != null && rows.length() == 0) {
            removeLocalItem(context, id);
            throw new ApiException(200, "gone", "该事项已在其他设备删除");
        }

        JSONObject saved = null;
        if (rows != null) {
            JSONObject first = rows.optJSONObject(0);
            if (first != null) saved = first.optJSONObject("payload");
        }
        JSONObject effective = saved == null ? item : new JSONObject(saved.toString());
        if (Items.isMeta(effective)) {
            updateLocalMeta(context, effective);
        } else {
            updateLocalItem(context, id, effective);
        }
    }

    static synchronized void setTodoDone(Context context, String id, JSONObject original,
                                         boolean done) throws Exception {
        if (id == null || id.isEmpty() || original == null) throw new IllegalArgumentException("无法识别事项。");
        patchPayload(context, id, Items.applyDone(original, done));
    }

    static synchronized void postpone(Context context, String id, JSONObject original,
                                      String targetIso) throws Exception {
        if (id == null || id.isEmpty() || original == null) throw new IllegalArgumentException("无法识别事项。");
        patchPayload(context, id, Items.applyPostpone(original, targetIso));
    }

    static synchronized void drop(Context context, String id, JSONObject original) throws Exception {
        if (id == null || id.isEmpty() || original == null) throw new IllegalArgumentException("无法识别事项。");
        patchPayload(context, id, Items.applyDrop(original));
    }

    static synchronized void restore(Context context, String id, JSONObject original) throws Exception {
        if (id == null || id.isEmpty() || original == null) throw new IllegalArgumentException("无法识别事项。");
        patchPayload(context, id, Items.applyRestore(original));
    }

    static synchronized void saveMeta(Context context, JSONObject updatedMeta) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        String token = accessToken(context, false);
        String userId = tokenUserId(token);
        if (userId.isEmpty()) throw new ApiException(401, "auth", "无法识别当前账号，请重新登录。");
        JSONObject meta = new JSONObject(updatedMeta.toString());
        meta.put("type", "meta");
        patchPayload(context, Items.metaId(userId), meta);
    }

    private static void updateLocalItem(Context context, String id, JSONObject payload) throws Exception {
        JSONObject cache = SessionStore.cache(context);
        JSONArray current = cache.optJSONArray("all_items");
        JSONArray next = new JSONArray();
        if (current != null) {
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                String rowId = item.optString("_row_id", item.optString("id", ""));
                if (!id.equals(rowId)) next.put(item);
            }
        }
        JSONObject stored = new JSONObject(payload.toString());
        stored.remove("id");
        stored.put("_row_id", id);
        next.put(stored);
        rebuildAndSave(context, next, cache.optJSONObject("meta"));
    }

    private static void removeLocalItem(Context context, String id) throws Exception {
        JSONObject cache = SessionStore.cache(context);
        JSONArray current = cache.optJSONArray("all_items");
        JSONArray next = new JSONArray();
        if (current != null) {
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                String rowId = item.optString("_row_id", item.optString("id", ""));
                if (!id.equals(rowId)) next.put(item);
            }
        }
        rebuildAndSave(context, next, cache.optJSONObject("meta"));
    }

    private static void updateLocalMeta(Context context, JSONObject meta) throws Exception {
        JSONObject cache = SessionStore.cache(context);
        JSONObject nextMeta = new JSONObject(meta.toString());
        nextMeta.remove("_row_id");
        nextMeta.remove("id");
        rebuildAndSave(context, cache.optJSONArray("all_items"), nextMeta);
    }

    private static void rebuildAndSave(Context context, JSONArray allItems, JSONObject meta) throws Exception {
        String today = LocalDate.now().toString();
        String syncedAt = LocalTime.now().withSecond(0).withNano(0).toString();
        JSONObject data = buildCache(allItems, meta, today, syncedAt, System.currentTimeMillis());
        SessionStore.saveCache(context, data);
    }

    static synchronized void deleteItem(Context context, String id) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("无法识别事项。");
        String path = "/rest/v1/calendar_items?id=eq." + URLEncoder.encode(id, "UTF-8");
        String token = accessToken(context, false);
        try {
            request(path, "DELETE", null, token);
        } catch (ApiException error) {
            if (error.status != 401) throw error;
            request(path, "DELETE", null, accessToken(context, true));
        }
    }

    private static String newRowId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String tokenUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return "";
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JSONObject claims = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return claims.optString("sub", "");
        } catch (Exception error) {
            return "";
        }
    }

    private static String format(String heading, List<JSONObject> items, boolean showPlace) {
        if (items.isEmpty()) return heading + "：无";
        StringBuilder result = new StringBuilder(heading).append("：");
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.get(i);
            if (i > 0) result.append('\n');
            String time = item.optString("time");
            if (!time.isEmpty()) result.append(time).append(' ');
            result.append(item.optString("title", "未命名"));
            String place = item.optString("place");
            if (place.isEmpty()) place = item.optString("room");
            if (showPlace && !place.isEmpty()) result.append(" · ").append(place);
        }
        return result.toString();
    }

    static final class ApiException extends Exception {
        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
