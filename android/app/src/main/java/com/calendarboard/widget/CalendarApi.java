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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class CalendarApi {
    private static final String BASE = "https://samnpjgnvtearyeejjtg.supabase.co";
    // Supabase publishable keys are designed for client apps. RLS protects each account's rows.
    private static final String KEY = "sb_publishable_x061oGvVUpjXv0PG1J-Kag_Voayt0Ux";

    private CalendarApi() { }

    private static JSONObject request(String path, String method, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("apikey", KEY);
        connection.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method) && path.startsWith("/rest/v1/calendar_items"))
            connection.setRequestProperty("Prefer", "resolution=merge-duplicates");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
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
                throw new ApiException(status, message);
            }
            if (text.startsWith("[")) return new JSONObject().put("rows", new JSONArray(text));
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally { connection.disconnect(); }
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
        JSONArray all = new JSONArray();
        for (int offset = 0; ; offset += 1000) {
            String path = "/rest/v1/calendar_items?select=id,payload&order=id.asc&limit=1000&offset=" + offset;
            JSONObject response;
            try { response = request(path, "GET", null, token); }
            catch (ApiException error) {
                if (error.status != 401) throw error;
                token = accessToken(context, true);
                response = request(path, "GET", null, token);
            }
            JSONArray rows = response.getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) all.put(rows.getJSONObject(i));
            if (rows.length() < 1000) break;
        }
        String today = LocalDate.now().toString();
        JSONArray weekSchedules = new JSONArray();
        JSONArray allItems = new JSONArray();
        List<JSONObject> courses = new ArrayList<>(), todos = new ArrayList<>(), schedules = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.getJSONObject(i);
            JSONObject item = row.optJSONObject("payload");
            if (item == null) continue;
            item.put("_row_id", row.optString("id"));
            allItems.put(item);
            String date = item.optString("date");
            String type = item.optString("type");
            if ("课程".equals(type) && today.equals(date)) courses.add(item);
            if ("待办".equals(type) && !item.optBoolean("done") && !date.isEmpty() && date.compareTo(today) <= 0) todos.add(item);
            if ("日程".equals(type) && today.equals(date)) schedules.add(item);
            if ("日程".equals(type) && date.compareTo(today) >= 0
                    && date.compareTo(LocalDate.parse(today).plusDays(7).toString()) <= 0) weekSchedules.put(row);
        }
        Comparator<JSONObject> byTime = Comparator.comparing(item -> item.optString("time", ""));
        courses.sort(byTime); todos.sort(byTime); schedules.sort(byTime);
        JSONObject data = new JSONObject()
                .put("date", today)
                .put("course", format("课程", courses, true))
                .put("todo", format("待办", todos, false))
                .put("schedule", format("日程", schedules, true))
                .put("week_schedules", weekSchedules)
                .put("all_items", allItems)
                .put("editable_items", editable(courses, todos, schedules))
                .put("synced_at", LocalTime.now().withSecond(0).withNano(0).toString());
        if (revision != SessionStore.revision()) throw new IllegalStateException("登录状态已改变。");
        SessionStore.saveCache(context, data);
        return data;
    }

    private static JSONArray editable(List<JSONObject> courses, List<JSONObject> todos, List<JSONObject> schedules) {
        JSONArray result = new JSONArray();
        for (JSONObject item : courses) result.put(item);
        for (JSONObject item : todos) result.put(item);
        for (JSONObject item : schedules) result.put(item);
        return result;
    }

    static synchronized void saveItem(Context context, String id, JSONObject original,
                                      String date, String type, String title, String time, String place) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        JSONObject item = original == null ? new JSONObject() : new JSONObject(original.toString());
        item.remove("_row_id");
        item.put("type", type).put("title", title).put("date", date).put("time", time).put("place", place);
        if ("待办".equals(type) && original == null) item.put("done", false);
        JSONObject row = new JSONObject().put("id", id == null ? UUID.randomUUID().toString().replace("-", "") : id)
                .put("payload", item);
        String token = accessToken(context, false);
        try { request("/rest/v1/calendar_items", "POST", row, token); }
        catch (ApiException error) {
            if (error.status != 401) throw error;
            request("/rest/v1/calendar_items", "POST", row, accessToken(context, true));
        }
    }

    static synchronized void setTodoDone(Context context, String id, JSONObject original, boolean done) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        if (id == null || id.isEmpty() || original == null) throw new IllegalArgumentException("无法识别事项。");
        JSONObject item = new JSONObject(original.toString());
        item.remove("_row_id");
        item.put("done", done);
        JSONObject row = new JSONObject().put("id", id).put("payload", item);
        String token = accessToken(context, false);
        try { request("/rest/v1/calendar_items", "POST", row, token); }
        catch (ApiException error) {
            if (error.status != 401) throw error;
            request("/rest/v1/calendar_items", "POST", row, accessToken(context, true));
        }
    }

    static synchronized void deleteItem(Context context, String id) throws Exception {
        if (SessionStore.session(context) == null) throw new IllegalStateException("请先登录。");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("无法识别事项。");
        String path = "/rest/v1/calendar_items?id=eq." + URLEncoder.encode(id, "UTF-8");
        String token = accessToken(context, false);
        try { request(path, "DELETE", null, token); }
        catch (ApiException error) {
            if (error.status != 401) throw error;
            request(path, "DELETE", null, accessToken(context, true));
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

    private static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
}


