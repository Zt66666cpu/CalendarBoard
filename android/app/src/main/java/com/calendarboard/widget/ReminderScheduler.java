package com.calendarboard.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single scheduling entry point for every reminder. Exact alarms are used when the platform
 * permits them and fall back to inexact idle-safe alarms otherwise.
 */
final class ReminderScheduler {
    private static final String TAG = "CalendarBoardScheduler";

    static final String KEY_NEXT_MORNING = "next_morning_ms";
    static final String KEY_NEXT_NOON = "next_noon_ms";
    static final String KEY_NEXT_EVENING = "next_evening_ms";
    static final String KEY_NEXT_AHEAD = "next_ahead_ms";
    static final String KEY_NEXT_REVIEW = "next_review_ms";
    static final String KEY_NEXT_ISLAND = "next_island_ms";

    private static final int REQ_MORNING = 4101;
    private static final int REQ_NOON = 4102;
    private static final int REQ_EVENING = 4103;
    private static final int REQ_AHEAD = 4104;
    private static final int REQ_REVIEW = 4105;
    private static final int REQ_ISLAND = 4106;

    private ReminderScheduler() { }

    static synchronized void rescheduleAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        Notifier.ensureChannels(app);
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            Log.w(TAG, "系统闹钟服务不可用，跳过重排");
            return;
        }

        JSONObject cache = SessionStore.cache(app);
        List<JSONObject> allItems = Notifier.allItems(cache);
        LocalDate todayDate = LocalDate.now();
        String today = todayDate.toString();
        LocalDateTime now = LocalDateTime.now();
        long nowMs = System.currentTimeMillis();

        SharedPreferences prefs = app.getSharedPreferences(Notifier.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        LocalDateTime morningAt = todayDate.atTime(7, 30);
        if (now.isAfter(morningAt)
                || today.equals(prefs.getString(Notifier.KEY_MORNING_SENT_DATE, ""))) {
            morningAt = morningAt.plusDays(1);
        }
        long morningMs = toMillis(morningAt);
        scheduleExactOrFallback(app, alarms, morningMs, REQ_MORNING,
                ReminderReceiver.ACTION_MORNING, null);
        editor.putLong(KEY_NEXT_MORNING, morningMs);

        LocalDateTime noonAt = todayDate.atTime(12, 0);
        if (!now.isBefore(noonAt)) noonAt = noonAt.plusDays(1);
        long noonMs = toMillis(noonAt);
        scheduleExactOrFallback(app, alarms, noonMs, REQ_NOON,
                ReminderReceiver.ACTION_NOON, null);
        editor.putLong(KEY_NEXT_NOON, noonMs);

        LocalDateTime eveningAt = todayDate.atTime(18, 0);
        if (!now.isBefore(eveningAt)) eveningAt = eveningAt.plusDays(1);
        long eveningMs = toMillis(eveningAt);
        scheduleExactOrFallback(app, alarms, eveningMs, REQ_EVENING,
                ReminderReceiver.ACTION_EVENING, null);
        editor.putLong(KEY_NEXT_EVENING, eveningMs);

        int trigger = Items.reviewTriggerMinutes(allItems, today);
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        long reviewMs = 0L;
        if (trigger > 1080 && nowMinutes < trigger) {
            LocalDateTime reviewAt = todayDate.atTime(trigger / 60, trigger % 60);
            reviewMs = toMillis(reviewAt);
            scheduleExactOrFallback(app, alarms, reviewMs, REQ_REVIEW,
                    ReminderReceiver.ACTION_REVIEW, null);
        } else {
            cancelAlarm(app, alarms, REQ_REVIEW, ReminderReceiver.ACTION_REVIEW);
        }
        editor.putLong(KEY_NEXT_REVIEW, reviewMs);

        Set<String> sentAhead = new HashSet<>();
        Set<String> stored = prefs.getStringSet(Notifier.KEY_AHEAD_SENT_PREFIX + today,
                Collections.<String>emptySet());
        if (stored != null) sentAhead.addAll(stored);

        long aheadMs = 0L;
        String aheadRowId = null;
        for (int offset = 0; offset <= 1; offset++) {
            String date = todayDate.plusDays(offset).toString();
            for (JSONObject item : allItems) {
                if (item == null || !date.equals(item.optString("date", ""))) continue;
                String type = item.optString("type", "");
                if (!"待办".equals(type) && !"日程".equals(type)) continue;
                if (Items.isArchived(item, today)) continue;
                Integer start = Items.itemStart(item);
                if (start == null) continue;
                String rowId = rowIdOf(item);
                if (rowId.isEmpty() || sentAhead.contains(rowId)) continue;
                LocalDate dateValue = LocalDate.parse(date);
                LocalDateTime fireAt = dateValue.atTime(start / 60, start % 60).minusMinutes(30);
                long fireMs = toMillis(fireAt);
                if (fireMs <= nowMs) continue;
                if (aheadMs == 0L || fireMs < aheadMs) {
                    aheadMs = fireMs;
                    aheadRowId = rowId;
                }
            }
        }
        if (aheadMs > 0L && aheadRowId != null) {
            scheduleExactOrFallback(app, alarms, aheadMs, REQ_AHEAD,
                    ReminderReceiver.ACTION_AHEAD, aheadRowId);
        } else {
            aheadMs = 0L;
            cancelAlarm(app, alarms, REQ_AHEAD, ReminderReceiver.ACTION_AHEAD);
        }
        editor.putLong(KEY_NEXT_AHEAD, aheadMs);

        long islandMs = 0L;
        if (Notifier.isIslandEnabled(app)) {
            islandMs = nextIslandMs(allItems, todayDate, nowMs);
            if (islandMs > 0L) {
                scheduleExactOrFallback(app, alarms, islandMs, REQ_ISLAND,
                        ReminderReceiver.ACTION_ISLAND, null);
            } else {
                cancelAlarm(app, alarms, REQ_ISLAND, ReminderReceiver.ACTION_ISLAND);
            }
        } else {
            cancelAlarm(app, alarms, REQ_ISLAND, ReminderReceiver.ACTION_ISLAND);
        }
        editor.putLong(KEY_NEXT_ISLAND, islandMs);

        editor.apply();
    }

    private static long nextIslandMs(List<JSONObject> allItems, LocalDate todayDate, long nowMs) {
        long best = Long.MAX_VALUE;
        String today = todayDate.toString();
        for (JSONObject item : allItems) {
            if (item == null || !"课程".equals(item.optString("type", ""))) continue;
            if (!today.equals(item.optString("date", ""))) continue;
            Integer start = Items.itemStart(item);
            if (start == null) continue;
            Integer end = Items.itemEnd(item);
            List<Integer> points = new ArrayList<>();
            for (int minute = start - 30; minute < start; minute += 5) {
                points.add(minute);
            }
            points.add(start);
            if (end != null) {
                for (int minute = start + 10; minute < end; minute += 10) {
                    points.add(minute);
                }
                points.add(end);
            }
            for (Integer point : points) {
                if (point == null || point < 0 || point >= 1440) continue;
                LocalDateTime at = todayDate.atTime(point / 60, point % 60);
                long atMs = toMillis(at);
                if (atMs > nowMs && atMs < best) best = atMs;
            }
        }
        return best == Long.MAX_VALUE ? 0L : best;
    }

    private static String rowIdOf(JSONObject item) {
        String id = item.optString("_row_id", "");
        if (id.isEmpty()) id = item.optString("id", "");
        return id;
    }

    private static long toMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static PendingIntent alarmIntent(Context context, int requestCode, String action,
                                             String rowId) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(action);
        if (rowId != null) intent.putExtra(ReminderReceiver.EXTRA_ROW_ID, rowId);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleInexact(Context context, AlarmManager alarms, long atMs,
                                        int requestCode, String action, String rowId) {
        if (atMs <= System.currentTimeMillis()) return;
        PendingIntent pending = alarmIntent(context, requestCode, action, rowId);
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
    }

    private static void scheduleExactOrFallback(Context context, AlarmManager alarms, long atMs,
                                                int requestCode, String action, String rowId) {
        if (atMs <= System.currentTimeMillis()) return;
        PendingIntent pending = alarmIntent(context, requestCode, action, rowId);
        boolean exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
        }
    }

    private static void cancelAlarm(Context context, AlarmManager alarms, int requestCode,
                                    String action) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        alarms.cancel(pending);
        pending.cancel();
    }
}
