package com.calendarboard.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Receives alarm, boot and time-change broadcasts. Network work runs off the main thread with an
 * eight second budget; every path falls back to the cached snapshot.
 */
public final class ReminderReceiver extends BroadcastReceiver {
    private static final String TAG = "CalendarBoardReminder";

    static final String ACTION_COMPLETE_TODO = "com.calendarboard.widget.ACTION_COMPLETE_TODO";
    static final String ACTION_MORNING = "com.calendarboard.widget.ACTION_MORNING";
    static final String ACTION_NOON = "com.calendarboard.widget.ACTION_NOON";
    static final String ACTION_EVENING = "com.calendarboard.widget.ACTION_EVENING";
    static final String ACTION_REVIEW = "com.calendarboard.widget.ACTION_REVIEW";
    static final String ACTION_AHEAD = "com.calendarboard.widget.ACTION_AHEAD";
    static final String ACTION_ISLAND = "com.calendarboard.widget.ACTION_ISLAND";

    static final String EXTRA_ROW_ID = "row_id";
    static final String EXTRA_NOTIFICATION_ID = "notification_id";

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService IO = Executors.newCachedThreadPool();

    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String action = intent == null ? null : intent.getAction();
        String rowId = intent == null ? "" : intent.getStringExtra(EXTRA_ROW_ID);
        int notificationId = intent == null
                ? 0
                : intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);

        final PendingResult pendingResult = goAsync();
        final Context target = app;
        final String receivedAction = action;
        final String receivedRowId = rowId == null ? "" : rowId;
        final int receivedNotificationId = notificationId;

        WORK.execute(() -> {
            try {
                if (ACTION_COMPLETE_TODO.equals(receivedAction)) {
                    completeTodo(target, receivedRowId, receivedNotificationId);
                } else {
                    handleBroadcast(target, receivedAction, receivedRowId);
                }
            } catch (Exception error) {
                Log.e(TAG, "处理提醒广播失败：" + receivedAction, error);
            } finally {
                pendingResult.finish();
            }
        });
    }

    private static void completeTodo(Context context, String rowId, int notificationId) {
        if (rowId.isEmpty()) return;
        JSONObject cache = SessionStore.cache(context);
        JSONObject item = findItem(cache, rowId);
        if (item == null) {
            Log.w(TAG, "未找到待完成事项：" + rowId);
            return;
        }
        try {
            CalendarApi.setTodoDone(context, rowId, item, true);
        } catch (Exception error) {
            Log.e(TAG, "完成待办失败：" + rowId, error);
            return;
        }
        NotificationManagerCompat.from(context).cancel(notificationId);
        CalendarWidget.renderAll(context);
        ReminderScheduler.rescheduleAll(context);
    }

    private static void handleBroadcast(Context context, String action, String rowId) {
        boolean syncSucceeded = false;
        if (SessionStore.session(context) != null) {
            Future<JSONObject> future = IO.submit(() -> CalendarApi.syncToday(context));
            try {
                future.get(8, TimeUnit.SECONDS);
                syncSucceeded = true;
            } catch (TimeoutException error) {
                future.cancel(true);
                Log.w(TAG, "同步超时，改用缓存");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "同步被中断，改用缓存");
            } catch (ExecutionException error) {
                Log.w(TAG, "同步失败，改用缓存", error.getCause());
            }
        }

        JSONObject cache = SessionStore.cache(context);
        String today = LocalDate.now().toString();
        if (Intent.ACTION_DATE_CHANGED.equals(action) && !today.equals(cache.optString("date", ""))) {
            cache = rebuildForToday(context, cache, today);
        }

        boolean readOnly = isReadOnly(cache);
        if (ACTION_MORNING.equals(action)) {
            Notifier.maybeSendMorning(context, cache, syncSucceeded);
        } else if (ACTION_NOON.equals(action)) {
            Notifier.maybeSendTodoReminder(context, cache, false);
        } else if (ACTION_EVENING.equals(action)) {
            Notifier.maybeSendTodoReminder(context, cache, true);
        } else if (ACTION_REVIEW.equals(action)) {
            Notifier.maybeSendReviewPrompt(context, cache);
        } else if (ACTION_AHEAD.equals(action)) {
            JSONObject item = findItem(cache, rowId);
            if (item != null) Notifier.sendAheadReminder(context, item, readOnly);
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            int hour = LocalTime.now().getHour();
            if (hour >= 5 && hour <= 11) {
                Notifier.maybeSendMorning(context, cache, syncSucceeded);
            }
        }

        ReminderScheduler.rescheduleAll(context);

        boolean refreshIsland = ACTION_ISLAND.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action);
        if (refreshIsland) {
            JSONObject latest = SessionStore.cache(context);
            Notifier.updateCourseIsland(context, latest);
            NextClassWidget.renderAll(context);
            CalendarWidget.renderAll(context);
        }
    }

    private static JSONObject rebuildForToday(Context context, JSONObject cache, String today) {
        try {
            JSONArray allItems = cache.optJSONArray("all_items");
            JSONObject meta = cache.optJSONObject("meta");
            String syncedAt = LocalTime.now().withSecond(0).withNano(0).toString();
            JSONObject rebuilt = CalendarApi.buildCache(allItems, meta, today, syncedAt,
                    System.currentTimeMillis());
            SessionStore.saveCache(context, rebuilt);
            Repo.get().onCacheSaved(rebuilt);
            return rebuilt;
        } catch (Exception error) {
            Log.e(TAG, "跨日重算缓存失败", error);
            return cache;
        }
    }

    private static boolean isReadOnly(JSONObject cache) {
        JSONObject meta = cache == null ? null : cache.optJSONObject("meta");
        return meta != null
                && meta.optInt("schemaVersion", Items.SUPPORTED_SCHEMA) > Items.SUPPORTED_SCHEMA;
    }

    private static JSONObject findItem(JSONObject cache, String rowId) {
        if (cache == null || rowId == null || rowId.isEmpty()) return null;
        JSONArray rows = cache.optJSONArray("all_items");
        if (rows == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("_row_id", "");
            if (id.isEmpty()) id = item.optString("id", "");
            if (rowId.equals(id)) return item;
        }
        return null;
    }
}
