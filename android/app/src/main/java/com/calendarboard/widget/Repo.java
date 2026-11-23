package com.calendarboard.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory source of truth for calendar data. Mutations replace a volatile immutable snapshot
 * and listeners are always notified on the main thread.
 */
final class Repo {
    private static final Repo INSTANCE = new Repo();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile Context appContext;

    private Repo() { }

    static Repo get() { return INSTANCE; }

    void loadFromCache(Context context) {
        if (context == null) throw new IllegalArgumentException("Context 不能为空");
        Context app = applicationContext(context);
        appContext = app;
        boolean loggedIn = SessionStore.session(app) != null;
        publish(SessionStore.cache(app), loggedIn);
    }

    void onCacheSaved(JSONObject cache) {
        JSONObject value = cache == null ? new JSONObject() : cache;
        Context context = appContext;
        if (context != null) {
            publish(value, SessionStore.session(context) != null);
            return;
        }
        // CalendarApi only saves caches while authenticated, so a saved cache without a cached
        // Context still means the user is logged in.
        publish(value, true);
    }

    void onCacheSaved(Context context, JSONObject cache) {
        if (context == null) throw new IllegalArgumentException("Context 不能为空");
        Context app = applicationContext(context);
        appContext = app;
        publish(cache == null ? new JSONObject() : cache, SessionStore.session(app) != null);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    void applyLocal(final String rowId, final JSONObject payloadOrNull) {
        if (rowId == null || rowId.isEmpty()) throw new IllegalArgumentException("无法识别事项。");
        Runnable update = () -> applyLocalOnMain(rowId, payloadOrNull);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            MAIN.post(update);
        }
    }

    void addListener(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener 不能为空");
        listeners.addIfAbsent(listener);
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void applyLocalOnMain(String rowId, JSONObject payloadOrNull) {
        Snapshot next;
        synchronized (lock) {
            Snapshot current = snapshot;
            List<JSONObject> items = new ArrayList<>();
            for (JSONObject item : current.items) {
                if (!rowId.equals(rowIdOf(item))) items.add(item);
            }
            if (payloadOrNull != null) items.add(withRowId(payloadOrNull, rowId));
            next = new Snapshot(current.loggedIn, current.readOnly, current.today,
                    current.syncedAt, current.syncedAtMs, current.meta, items);
            snapshot = next;
        }
        notifyListeners(next);
    }

    private void publish(JSONObject cache, boolean loggedIn) {
        Snapshot next;
        synchronized (lock) {
            next = build(cache, loggedIn);
            snapshot = next;
        }
        notifyListeners(next);
    }

    private void notifyListeners(final Snapshot value) {
        Runnable notify = () -> {
            for (Listener listener : listeners) listener.onRepoChanged(value);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify.run();
        } else {
            MAIN.post(notify);
        }
    }

    private static Snapshot build(JSONObject cache, boolean loggedIn) {
        JSONObject value = cache == null ? new JSONObject() : cache;
        JSONObject meta = value.optJSONObject("meta");
        boolean readOnly = meta != null
                && meta.optInt("schemaVersion", Items.SUPPORTED_SCHEMA) > Items.SUPPORTED_SCHEMA;
        String today = value.optString("date", "");
        if (today.isEmpty()) today = LocalDate.now().toString();
        String syncedAt = value.optString("synced_at", "");
        long syncedAtMs = value.optLong("synced_at_ms", 0L);

        JSONArray all = value.optJSONArray("all_items");
        if (all == null) all = value.optJSONArray("editable_items");
        List<JSONObject> items = new ArrayList<>();
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item != null && !Items.isMeta(item)) items.add(item);
            }
        }
        return new Snapshot(loggedIn, readOnly, today, syncedAt, syncedAtMs, meta, items);
    }

    private static Context applicationContext(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static String rowIdOf(JSONObject item) {
        String rowId = item.optString("_row_id", "");
        return rowId.isEmpty() ? item.optString("id", "") : rowId;
    }

    private static JSONObject withRowId(JSONObject payload, String rowId) {
        try {
            JSONObject stored = new JSONObject(payload.toString());
            stored.remove("id");
            stored.put("_row_id", rowId);
            return stored;
        } catch (JSONException error) {
            throw new IllegalStateException("无法更新本地事项", error);
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IllegalStateException("无法复制本地数据", error);
        }
    }

    interface Listener {
        void onRepoChanged(Snapshot snapshot);
    }

    /** Immutable structural snapshot of the cache; collections cannot be modified. */
    static final class Snapshot {
        final boolean loggedIn;
        final boolean readOnly;
        final String today;
        final String syncedAt;
        final long syncedAtMs;
        final JSONObject meta;
        final List<JSONObject> items;
        final Map<String, List<JSONObject>> byDate;

        Snapshot(boolean loggedIn, boolean readOnly, String today, String syncedAt,
                 long syncedAtMs, JSONObject meta, List<JSONObject> items) {
            this.loggedIn = loggedIn;
            this.readOnly = readOnly;
            this.today = today == null ? "" : today;
            this.syncedAt = syncedAt == null ? "" : syncedAt;
            this.syncedAtMs = syncedAtMs;
            this.meta = meta == null ? new JSONObject() : copy(meta);

            List<JSONObject> safeItems = new ArrayList<>();
            if (items != null) {
                for (JSONObject item : items) {
                    if (item != null && !Items.isMeta(item)) safeItems.add(copy(item));
                }
            }
            this.items = Collections.unmodifiableList(safeItems);

            Map<String, List<JSONObject>> grouped = new LinkedHashMap<>();
            for (JSONObject item : safeItems) {
                String date = item.optString("date", "");
                if (date.isEmpty()) continue;
                List<JSONObject> bucket = grouped.get(date);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    grouped.put(date, bucket);
                }
                bucket.add(item);
            }
            Map<String, List<JSONObject>> dates = new HashMap<>();
            for (Map.Entry<String, List<JSONObject>> entry : grouped.entrySet()) {
                List<JSONObject> bucket = entry.getValue();
                bucket.sort(Items.BY_TIME);
                dates.put(entry.getKey(), Collections.unmodifiableList(bucket));
            }
            this.byDate = Collections.unmodifiableMap(dates);
        }

        static Snapshot empty() {
            return new Snapshot(false, false, LocalDate.now().toString(), "", 0L,
                    new JSONObject(), Collections.<JSONObject>emptyList());
        }
    }
}
