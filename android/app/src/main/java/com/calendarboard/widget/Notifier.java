package com.calendarboard.widget;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds and posts the four reminder notification channels. All entry points are safe to call
 * from a background thread.
 */
final class Notifier {
    private static final String TAG = "CalendarBoardNotifier";

    static final String PREFS = "calendar_widget_reminders";

    static final String CHANNEL_MORNING = "morning";
    static final String CHANNEL_TODO = "todo";
    static final String CHANNEL_AHEAD = "ahead";
    static final String CHANNEL_ISLAND = "island";
    static final String CHANNEL_SUMMARY = "summary";

    static final String KEY_MORNING_SENT_DATE = "morning_sent_date";
    static final String KEY_LAST_REVIEW_PROMPT_DATE = "last_review_prompt_date";
    static final String KEY_SUMMARY_SENT_DATE = "summary_sent_date";
    static final String KEY_AHEAD_SENT_PREFIX = "ahead_sent_";
    static final String KEY_ISLAND_ENABLED = "island_enabled";
    static final String KEY_ISLAND_DISMISSED_KEY = "island_dismissed_key";

    private static final int ID_MORNING = 1001;
    private static final int ID_TODO = 2001;
    private static final int ID_REVIEW = 3001;
    private static final int ID_ISLAND = 4001;
    private static final int ID_SUMMARY = 5001;
    private static final int ID_TEST_MORNING = 9001;
    private static final int ID_TEST_TODO = 9002;
    private static final int ID_TEST_AHEAD = 9003;
    private static final int ID_TEST_ISLAND = 9004;

    private static final int REQ_OPEN_MAIN = 7101;
    private static final int REQ_OPEN_REVIEW = 7102;
    private static final int REQ_ISLAND_DISMISSED = 7103;
    private static final int REQ_ISLAND_COMPLETE = 7104;
    private static final DateTimeFormatter HH_MM =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private Notifier() { }

    static void ensureChannels(Context context) {
        if (context == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel morning = new NotificationChannel(
                CHANNEL_MORNING, "早安摘要", NotificationManager.IMPORTANCE_DEFAULT);
        morning.setDescription("每天早上的课程、日程与待办摘要");
        manager.createNotificationChannel(morning);

        NotificationChannel todo = new NotificationChannel(
                CHANNEL_TODO, "待办提醒", NotificationManager.IMPORTANCE_DEFAULT);
        todo.setDescription("中午与傍晚的待办提醒，以及每日回顾");
        manager.createNotificationChannel(todo);

        NotificationChannel ahead = new NotificationChannel(
                CHANNEL_AHEAD, "提前提醒", NotificationManager.IMPORTANCE_HIGH);
        ahead.setDescription("事项开始前 30 分钟的提醒");
        manager.createNotificationChannel(ahead);

        NotificationChannel island = new NotificationChannel(
                CHANNEL_ISLAND, "灵动岛", NotificationManager.IMPORTANCE_LOW);
        island.setDescription("事项开始前 30 分钟到结束的实时状态提示");
        island.setSound(null, null);
        island.enableVibration(false);
        manager.createNotificationChannel(island);

        NotificationChannel summary = new NotificationChannel(
                CHANNEL_SUMMARY, "晚间总结", NotificationManager.IMPORTANCE_DEFAULT);
        summary.setDescription("每晚汇总今日完成、未完成待办与明日课程");
        manager.createNotificationChannel(summary);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isIslandEnabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ISLAND_ENABLED, true);
    }

    static void setIslandEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ISLAND_ENABLED, enabled).apply();
        if (!enabled) cancelCourseIsland(context);
    }

    static boolean canPostPromoted(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 36) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canPostPromotedNotifications();
    }

    /** Android 16 上返回用户是否允许上岛；更低版本返回 true（不适用，不提示） */
    static boolean promotionAllowedOrNotApplicable(Context c) {
        return Build.VERSION.SDK_INT < 36 || canPostPromoted(c);
    }

    static void cancelCourseIsland(Context context) {
        if (context == null) return;
        NotificationManagerCompat.from(context).cancel(ID_ISLAND);
    }

    static void markIslandDismissed(Context context, String dismissKey) {
        if (context == null || dismissKey == null || dismissKey.isEmpty()) return;
        prefs(context).edit().putString(KEY_ISLAND_DISMISSED_KEY, dismissKey).apply();
        cancelCourseIsland(context);
    }

    /**
     * Keeps one Live Updates island in sync with courses, schedules, and timed todos inside the
     * 30-minute lead window through end time. Outside any window, cancels the notification.
     */
    static void updateIsland(Context context, JSONObject cache) {
        if (context == null) return;
        if (!isIslandEnabled(context)) {
            cancelCourseIsland(context);
            return;
        }
        ensureChannels(context);

        LocalDateTime now = LocalDateTime.now();
        IslandPlanner.Plan plan = IslandPlanner.plan(allItems(cache), now);
        if (plan.kind == IslandPlanner.Kind.NONE || plan.item == null) {
            cancelCourseIsland(context);
            return;
        }

        String dismissKey = IslandPlanner.dismissKey(plan.item, now.toLocalDate());
        if (!dismissKey.isEmpty()
                && dismissKey.equals(prefs(context).getString(KEY_ISLAND_DISMISSED_KEY, ""))) {
            return;
        }

        boolean readOnly = isReadOnly(cache);
        postIsland(context, plan, ID_ISLAND, 0L, dismissKey, readOnly);
    }

    static void postIsland(Context context, IslandPlanner.Plan plan, int notificationId,
                           long timeoutMs, String dismissKey, boolean readOnly) {
        if (context == null || plan == null || plan.kind == IslandPlanner.Kind.NONE
                || plan.item == null) {
            if (notificationId == ID_ISLAND) cancelCourseIsland(context);
            return;
        }

        JSONObject item = plan.item;
        String type = item.optString("type", "课程");
        String title = item.optString("title", "未命名");
        String place = placeOf(item);
        boolean active = plan.kind == IslandPlanner.Kind.ACTIVE;

        String heading;
        String baseLine;
        String startText = formatEpochTime(plan.startMs);
        if (active) {
            heading = place.isEmpty() ? title : title + " · " + place;
            baseLine = startText + "–" + formatEpochTime(plan.targetMs);
        } else {
            if ("日程".equals(type)) heading = "日程 · " + title;
            else if ("待办".equals(type)) heading = "待办 · " + title;
            else heading = title;
            baseLine = place.isEmpty() ? startText : startText + " · " + place;
        }

        StringBuilder expanded = new StringBuilder(baseLine);
        if (!plan.nextUp.isEmpty()) {
            expanded.append("\n接下来：");
            for (int i = 0; i < plan.nextUp.size(); i++) {
                if (i > 0) expanded.append(" · ");
                expanded.append(nextUpBrief(plan.nextUp.get(i)));
            }
        }

        String publicText = active
                ? "有正在进行的安排"
                : ("马上".equals(plan.shortText) ? "马上有安排" : plan.shortText + "有安排");
        NotificationCompat.Builder publicNote = new NotificationCompat.Builder(context, CHANNEL_ISLAND)
                .setSmallIcon(R.drawable.ic_stat_calendar)
                .setContentTitle("课表日历")
                .setContentText(publicText)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        boolean promoted = canPostPromoted(context);
        NotificationCompat.Builder note = new NotificationCompat.Builder(context, CHANNEL_ISLAND)
                .setSmallIcon(R.drawable.ic_stat_calendar)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(active
                        ? NotificationCompat.CATEGORY_PROGRESS
                        : NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNote.build())
                .setContentTitle(heading)
                .setContentText(baseLine)
                .setContentIntent(openIntent(context, false));

        if (notificationId == ID_ISLAND && dismissKey != null && !dismissKey.isEmpty()) {
            Intent dismissed = new Intent(context, ReminderReceiver.class)
                    .setAction(ReminderReceiver.ACTION_ISLAND_DISMISSED)
                    .putExtra(ReminderReceiver.EXTRA_DISMISS_KEY, dismissKey);
            PendingIntent deleteIntent = PendingIntent.getBroadcast(context, REQ_ISLAND_DISMISSED,
                    dismissed, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            note.setDeleteIntent(deleteIntent);
        }

        if (promoted) {
            note.setRequestPromotedOngoing(true).setShortCriticalText(plan.shortText);
        }

        if (active) {
            int duration = Math.max(1, plan.durationMinutes);
            int elapsed = Math.max(0, Math.min(duration, plan.elapsedMinutes));
            int percent = Math.max(0, Math.min(100, elapsed * 100 / duration));
            if (promoted) {
                NotificationCompat.ProgressStyle style = new NotificationCompat.ProgressStyle()
                        .setStyledByProgress(true);
                if ("课程".equals(type) && duration == IslandPlanner.DEFAULT_COURSE_MIN) {
                    style.addProgressSegment(new NotificationCompat.ProgressStyle.Segment(
                                    IslandPlanner.LESSON_MIN))
                            .addProgressSegment(new NotificationCompat.ProgressStyle.Segment(
                                    IslandPlanner.BREAK_MIN)
                                    .setColor(KnitTheme.BORDER_LINEN))
                            .addProgressSegment(new NotificationCompat.ProgressStyle.Segment(
                                    IslandPlanner.LESSON_MIN))
                            .setProgress(elapsed);
                } else {
                    style.addProgressSegment(new NotificationCompat.ProgressStyle.Segment(100))
                            .setProgress(percent);
                }
                note.setStyle(style);
            } else {
                note.setProgress(100, percent, false);
                if (!plan.nextUp.isEmpty()) {
                    note.setStyle(new NotificationCompat.BigTextStyle()
                            .setBigContentTitle(heading)
                            .bigText(expanded.toString()));
                }
            }
        } else {
            note.setStyle(new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(heading)
                    .bigText(expanded.toString()));
        }

        if ("待办".equals(type) && !readOnly && notificationId == ID_ISLAND) {
            String rowId = rowIdOf(item);
            if (!rowId.isEmpty()) {
                Intent complete = new Intent(context, ReminderReceiver.class)
                        .setAction(ReminderReceiver.ACTION_COMPLETE_TODO)
                        .putExtra(ReminderReceiver.EXTRA_ROW_ID, rowId)
                        .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId);
                PendingIntent completeIntent = PendingIntent.getBroadcast(context,
                        REQ_ISLAND_COMPLETE, complete,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                note.addAction(R.drawable.ic_check, "完成", completeIntent);
            }
        }

        if (timeoutMs > 0L) {
            note.setTimeoutAfter(timeoutMs);
        }

        deliver(context, notificationId, note);
    }

    private static String formatEpochTime(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(HH_MM);
    }

    private static String nextUpBrief(JSONObject item) {
        if (item == null) return "";
        Integer start = Items.itemStart(item);
        String title = item.optString("title", "未命名");
        return start == null ? title : Items.formatMinutes(start) + " " + title;
    }

    private static boolean isReadOnly(JSONObject cache) {
        JSONObject meta = cache == null ? null : cache.optJSONObject("meta");
        return meta != null
                && meta.optInt("schemaVersion", Items.SUPPORTED_SCHEMA) > Items.SUPPORTED_SCHEMA;
    }

    static List<JSONObject> allItems(JSONObject cache) {
        List<JSONObject> result = new ArrayList<>();
        if (cache == null) return result;
        JSONArray rows = cache.optJSONArray("all_items");
        if (rows == null) return result;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null && !Items.isMeta(item)) result.add(item);
        }
        return result;
    }

    private static String rowIdOf(JSONObject item) {
        if (item == null) return "";
        String id = item.optString("_row_id", "");
        if (id.isEmpty()) id = item.optString("id", "");
        return id;
    }

    private static String placeOf(JSONObject item) {
        String place = item.optString("place", "");
        if (place.isEmpty()) place = item.optString("room", "");
        return place;
    }

    private static PendingIntent openIntent(Context context, boolean review) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (review) intent.putExtra(MainActivity.EXTRA_OPEN_REVIEW, true);
        int requestCode = review ? REQ_OPEN_REVIEW : REQ_OPEN_MAIN;
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationCompat.Builder builder(Context context, String channel) {
        return new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_calendar)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, false));
    }

    private static void deliver(Context context, int notificationId,
                                NotificationCompat.Builder builder) {
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
        } catch (SecurityException error) {
            Log.w(TAG, "通知权限不足，无法发送通知 " + notificationId, error);
        }
    }

    private static String itemLine(JSONObject item) {
        StringBuilder line = new StringBuilder();
        Integer start = Items.itemStart(item);
        if (start != null) line.append(Items.formatMinutes(start)).append(' ');
        line.append(item.optString("title", "未命名"));
        String place = placeOf(item);
        if (!place.isEmpty()) line.append(" · ").append(place);
        return line.toString();
    }

    /**
     * Morning summary. A failed sync with a stale cache will not mark the day as sent so a later
     * successful trigger can still deliver it.
     */
    static void maybeSendMorning(Context context, JSONObject cache, boolean syncSucceeded) {
        if (context == null) return;
        ensureChannels(context);
        String today = LocalDate.now().toString();
        SharedPreferences prefs = prefs(context);
        if (today.equals(prefs.getString(KEY_MORNING_SENT_DATE, ""))) return;

        JSONObject value = cache == null ? new JSONObject() : cache;
        if (!syncSucceeded && !today.equals(value.optString("date", ""))) return;

        List<JSONObject> allItems = allItems(value);
        Items.DayItemsResult day = Items.dayItems(allItems, today, today);
        int courseCount = day.courses.size();
        int scheduleCount = day.schedules.size();
        int todoCount = day.todos.size();
        int pendingCount = day.pending.size();

        if (courseCount == 0 && scheduleCount == 0 && todoCount == 0 && pendingCount == 0) {
            prefs.edit().putString(KEY_MORNING_SENT_DATE, today).apply();
            return;
        }

        StringBuilder counts = new StringBuilder();
        if (courseCount > 0) counts.append(courseCount).append(" 节课");
        if (scheduleCount > 0) {
            if (counts.length() > 0) counts.append(" · ");
            counts.append(scheduleCount).append(" 个日程");
        }
        if (todoCount > 0) {
            if (counts.length() > 0) counts.append(" · ");
            counts.append(todoCount).append(" 项待办");
        }
        String body = counts.toString();
        if (pendingCount > 0) {
            if (!body.isEmpty()) body += "，";
            body += "另有 " + pendingCount + " 项待处理";
        }

        List<JSONObject> overview = new ArrayList<>();
        overview.addAll(day.courses);
        overview.addAll(day.schedules);
        overview.addAll(day.todos);
        overview.sort(Items.BY_TIME);

        StringBuilder bigText = new StringBuilder();
        int shown = 0;
        for (JSONObject item : overview) {
            if (shown >= 8) break;
            if (bigText.length() > 0) bigText.append('\n');
            bigText.append(itemLine(item));
            shown++;
        }
        if (overview.size() > shown) {
            if (bigText.length() > 0) bigText.append('\n');
            bigText.append("还有 ").append(overview.size() - shown).append(" 项");
        }

        String title = "今天 · " + CalendarUtils.shortDateHeading(LocalDate.parse(today));
        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(bigText.toString());
        NotificationCompat.Builder note = builder(context, CHANNEL_MORNING)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(style);
        deliver(context, ID_MORNING, note);
        prefs.edit().putString(KEY_MORNING_SENT_DATE, today).apply();
    }

    /**
     * Noon and evening todo reminder. The evening edition merges the review prompt when the
     * review trigger happens at 18:00.
     */
    static void maybeSendTodoReminder(Context context, JSONObject cache, boolean isEvening) {
        if (context == null) return;
        ensureChannels(context);
        String today = LocalDate.now().toString();
        List<JSONObject> allItems = allItems(cache);
        List<JSONObject> pending = new ArrayList<>();
        for (JSONObject item : allItems) {
            if (item == null || !"待办".equals(item.optString("type", ""))) continue;
            if (Items.isDone(item) || Items.isDropped(item)) continue;
            String date = item.optString("date", "");
            if (date.isEmpty() || date.compareTo(today) > 0) continue;
            pending.add(item);
        }
        if (pending.isEmpty()) return;

        pending.sort((a, b) -> {
            int dateOrder = a.optString("date", "").compareTo(b.optString("date", ""));
            return dateOrder != 0 ? dateOrder : Items.BY_TIME.compare(a, b);
        });

        String title = "还有 " + pending.size() + " 项待办";
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
                .setBigContentTitle(title);
        int shown = 0;
        for (JSONObject item : pending) {
            if (shown >= 6) break;
            style.addLine(item.optString("title", "未命名"));
            shown++;
        }
        if (pending.size() > shown) style.setSummaryText("另有 " + pending.size() + " 项");

        NotificationCompat.Builder note = builder(context, CHANNEL_TODO)
                .setContentTitle(title)
                .setContentText("点击查看并处理今天的待办")
                .setStyle(style);

        boolean includeReview = false;
        if (isEvening && Items.reviewTriggerMinutes(allItems, today) == 1080) {
            List<JSONObject> review = Items.reviewList(allItems, today, Integer.valueOf(1080));
            includeReview = !review.isEmpty();
        }
        if (includeReview) {
            note.addAction(R.drawable.ic_check, "去回顾", openIntent(context, true));
        }

        deliver(context, ID_TODO, note);
        if (includeReview) {
            prefs(context).edit().putString(KEY_LAST_REVIEW_PROMPT_DATE, today).apply();
        }
    }

    /** Independent daily review reminder for trigger times later than 18:00. */
    static void maybeSendReviewPrompt(Context context, JSONObject cache) {
        if (context == null) return;
        ensureChannels(context);
        String today = LocalDate.now().toString();
        SharedPreferences prefs = prefs(context);
        if (today.equals(prefs.getString(KEY_LAST_REVIEW_PROMPT_DATE, ""))) return;

        List<JSONObject> allItems = allItems(cache);
        int nowMinutes = java.time.LocalTime.now().getHour() * 60
                + java.time.LocalTime.now().getMinute();
        List<JSONObject> review = Items.reviewList(allItems, today, Integer.valueOf(nowMinutes));
        if (review.isEmpty()) return;

        String title = "今天还有 " + review.size() + " 项没处理";
        NotificationCompat.Builder note = builder(context, CHANNEL_TODO)
                .setContentTitle(title)
                .setContentText("点开今日回顾，安排剩下的时间")
                .setContentIntent(openIntent(context, true));
        deliver(context, ID_REVIEW, note);
        prefs.edit().putString(KEY_LAST_REVIEW_PROMPT_DATE, today).apply();
    }

    /** Daily evening summary posted at 21:30 by default, at most once per calendar day. */
    static void maybeSendEveningSummary(Context context, JSONObject cache) {
        if (context == null) return;
        ensureChannels(context);
        LocalDate todayDate = LocalDate.now();
        String today = todayDate.toString();
        String tomorrow = todayDate.plusDays(1).toString();
        SharedPreferences prefs = prefs(context);
        if (today.equals(prefs.getString(KEY_SUMMARY_SENT_DATE, ""))) return;

        List<JSONObject> allItems = allItems(cache);
        int completedCount = 0;
        int pendingCount = 0;
        List<JSONObject> tomorrowCourses = new ArrayList<>();

        for (JSONObject item : allItems) {
            if (item == null) continue;
            String type = item.optString("type", "");
            if ("待办".equals(type)) {
                if (Items.isDone(item)) {
                    String completedAt = item.optString("completedAt", "").trim();
                    if (!completedAt.isEmpty()) {
                        try {
                            LocalDate doneDate = OffsetDateTime.parse(completedAt)
                                    .atZoneSameInstant(ZoneId.systemDefault())
                                    .toLocalDate();
                            if (todayDate.equals(doneDate)) completedCount++;
                        } catch (Exception error) {
                            Log.w(TAG, "无法解析 completedAt：" + completedAt, error);
                        }
                    }
                } else if (!Items.isDropped(item)) {
                    String date = item.optString("date", "");
                    if (!date.isEmpty() && date.compareTo(today) <= 0) {
                        pendingCount++;
                    }
                }
            } else if ("课程".equals(type) && tomorrow.equals(item.optString("date", ""))) {
                tomorrowCourses.add(item);
            }
        }

        int courseCount = tomorrowCourses.size();
        if (completedCount == 0 && pendingCount == 0 && courseCount == 0) {
            return;
        }
        tomorrowCourses.sort(Items.BY_TIME);

        String title = completedCount > 0 ? "今天完成 " + completedCount + " 项" : "今天的总结";
        String line1 = pendingCount > 0
                ? "还有 " + pendingCount + " 项待办没完成"
                : "待办全部清空 🎉";
        String line2;
        if (courseCount > 0) {
            JSONObject first = tomorrowCourses.get(0);
            Integer start = Items.itemStart(first);
            String firstTime = start == null ? "" : Items.formatMinutes(start);
            String firstTitle = first.optString("title", "未命名");
            line2 = "明天 " + courseCount + " 节课，第一节 "
                    + (firstTime.isEmpty() ? "" : firstTime + " ") + firstTitle;
        } else {
            line2 = "明天没有课";
        }

        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(line1 + "\n" + line2);
        NotificationCompat.Builder note = builder(context, CHANNEL_SUMMARY)
                .setContentTitle(title)
                .setContentText(line1 + " · " + line2)
                .setStyle(style);
        if (pendingCount > 0) {
            note.addAction(R.drawable.ic_check, "去回顾", openIntent(context, true));
        }

        deliver(context, ID_SUMMARY, note);
        prefs.edit().putString(KEY_SUMMARY_SENT_DATE, today).apply();
    }

    /** Pre-start reminder for one todo or schedule item. Each row fires at most once per day. */
    static void sendAheadReminder(Context context, JSONObject item, boolean readOnly) {
        if (context == null || item == null) return;
        ensureChannels(context);
        String today = LocalDate.now().toString();
        String rowId = rowIdOf(item);
        String key = rowId.isEmpty()
                ? item.optString("title", "事项") + "@" + item.optString("date", "")
                : rowId;

        SharedPreferences prefs = prefs(context);
        String sentKey = KEY_AHEAD_SENT_PREFIX + today;
        Set<String> oldSent = prefs.getStringSet(sentKey, Collections.<String>emptySet());
        if (oldSent != null && oldSent.contains(key)) return;

        String startTime = Items.buildTime(item.optString("startTime", ""),
                item.optString("endTime", ""));
        if (startTime.isEmpty()) startTime = item.optString("time", "");
        String place = placeOf(item);
        StringBuilder body = new StringBuilder(startTime);
        if (!place.isEmpty()) {
            if (body.length() > 0) body.append(" · ");
            body.append(place);
        }

        int notificationId = rowId.hashCode();
        NotificationCompat.Builder note = builder(context, CHANNEL_AHEAD)
                .setContentTitle("30 分钟后 · " + item.optString("title", "事项"))
                .setContentText(body.toString());

        if ("待办".equals(item.optString("type", "")) && !readOnly) {
            Intent complete = new Intent(context, ReminderReceiver.class)
                    .setAction(ReminderReceiver.ACTION_COMPLETE_TODO)
                    .putExtra(ReminderReceiver.EXTRA_ROW_ID, rowId)
                    .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId);
            PendingIntent completeIntent = PendingIntent.getBroadcast(context, notificationId,
                    complete, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            note.addAction(R.drawable.ic_check, "完成", completeIntent);
        }

        deliver(context, notificationId, note);

        Set<String> nextSent = new HashSet<>();
        if (oldSent != null) nextSent.addAll(oldSent);
        nextSent.add(key);
        prefs.edit().putStringSet(sentKey, nextSent).apply();
    }

    static void sendTestNotifications(Context context) {
        if (context == null) return;
        ensureChannels(context);

        deliver(context, ID_TEST_MORNING, builder(context, CHANNEL_MORNING)
                .setContentTitle("测试 · 早安摘要")
                .setContentText("今天的课程与待办会在这里汇总"));
        deliver(context, ID_TEST_TODO, builder(context, CHANNEL_TODO)
                .setContentTitle("测试 · 待办提醒")
                .setContentText("中午与傍晚的待办提醒会走这个渠道"));
        deliver(context, ID_TEST_AHEAD, builder(context, CHANNEL_AHEAD)
                .setContentTitle("测试 · 提前提醒")
                .setContentText("事项开始前 30 分钟会提醒你"));

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime fakeStart = now.plusMinutes(10);
        LocalDateTime fakeEnd = now.plusMinutes(55);
        JSONObject fakeCourse = new JSONObject();
        try {
            fakeCourse.put("_row_id", "test_island_course");
            fakeCourse.put("type", "课程");
            fakeCourse.put("date", fakeStart.toLocalDate().toString());
            fakeCourse.put("title", "测试课程");
            fakeCourse.put("startTime", fakeStart.toLocalTime().format(HH_MM));
            if (fakeEnd.toLocalDate().equals(fakeStart.toLocalDate())) {
                fakeCourse.put("endTime", fakeEnd.toLocalTime().format(HH_MM));
            }
        } catch (Exception ignored) { }
        IslandPlanner.Plan testPlan = IslandPlanner.plan(
                Collections.singletonList(fakeCourse), now);
        postIsland(context, testPlan, ID_TEST_ISLAND, 60_000L, null, true);
    }
}
