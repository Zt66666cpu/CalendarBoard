package com.calendarboard.widget;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;

public final class CalendarWidget extends AppWidgetProvider {
    static final String REFRESH = "com.calendarboard.widget.REFRESH";
    static final String ACTION_TOGGLE_TODO = "com.calendarboard.widget.ACTION_TOGGLE_TODO";
    static final String ACTION_OPEN_APP = "com.calendarboard.widget.ACTION_OPEN_APP";
    static final String EXTRA_ROW_ID = "row_id";

    private static final int IMMEDIATE_JOB = 101;
    private static final int PERIODIC_JOB = 102;
    static final int ISLAND_GUARD_JOB = 103;
    private static final String TAG = "CalendarBoardWidget";

    private static String widgetBody(String value, String heading, String emptyText) {
        String body = value.startsWith(heading + "：") ? value.substring(heading.length() + 1) : value;
        if (body.isEmpty() || "无".equals(body)) return emptyText;
        return "· " + body.replace("\n", "\n· ");
    }

    @Override public void onEnabled(Context context) { schedulePeriodic(context); }

    @Override public void onDisabled(Context context) {
        // Keep periodic sync alive even when no desktop widget is placed so reminders stay fresh.
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        schedulePeriodic(context);
        scheduleNow(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (REFRESH.equals(action)) {
            renderAll(context);
            scheduleNow(context);
        } else if (ACTION_TOGGLE_TODO.equals(action)) {
            toggleTodo(context, intent.getStringExtra(EXTRA_ROW_ID));
        } else if (ACTION_OPEN_APP.equals(action)) {
            openMain(context);
        }
    }

    private void toggleTodo(Context context, String rowId) {
        if (rowId == null || rowId.isEmpty()) return;
        final PendingResult pendingResult = goAsync();
        final Context app = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject cache = SessionStore.cache(app);
                JSONObject item = findItem(cache, rowId);
                if (item == null) {
                    Log.w(TAG, "找不到待办：" + rowId);
                    return;
                }
                CalendarApi.setTodoDone(app, rowId, item, true);
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                int[] ids = manager.getAppWidgetIds(new ComponentName(app, CalendarWidget.class));
                if (ids.length > 0) {
                    manager.notifyAppWidgetViewDataChanged(ids, R.id.todoList);
                }
                renderAll(app);
            } catch (Exception error) {
                Log.e(TAG, "勾选组件待办失败：" + rowId, error);
            } finally {
                pendingResult.finish();
            }
        }, "calendar-widget-toggle").start();
    }

    private static void openMain(Context context) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            pending.send();
        } catch (PendingIntent.CanceledException error) {
            Log.e(TAG, "无法打开主应用", error);
        }
    }

    private static JSONObject findItem(JSONObject cache, String rowId) {
        JSONArray rows = cache == null ? null : cache.optJSONArray("all_items");
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

    static void scheduleNow(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job = new JobInfo.Builder(IMMEDIATE_JOB, new ComponentName(context, SyncService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(1000)
                .build();
        jobs.schedule(job);
    }

    static void schedulePeriodic(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) return;
        if (jobs.getPendingJob(PERIODIC_JOB) == null) {
            JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, SyncService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60 * 1000L)
                    .build();
            jobs.schedule(job);
        }
        // 灵动岛守护：不要求联网，只用缓存重算并补挂闹钟链。闹钟链若被系统丢掉，最迟约 15 分钟内自愈。
        if (jobs.getPendingJob(ISLAND_GUARD_JOB) == null) {
            JobInfo guard = new JobInfo.Builder(ISLAND_GUARD_JOB,
                    new ComponentName(context, SyncService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .build();
            jobs.schedule(guard);
        }
    }

    static void renderAll(Context context) {
        KnitTheme.load(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, CalendarWidget.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return;

        JSONObject cached = SessionStore.cache(context);
        boolean loggedIn = SessionStore.session(context) != null;
        LocalDate today = LocalDate.now();
        boolean current = today.toString().equals(cached.optString("date"));

        String course = loggedIn ? (current ? widgetBody(cached.optString("course", "正在同步"), "课程", "今天没有课程") : "正在同步") : "轻点卡片登录账号";
        String schedule = loggedIn ? (current ? widgetBody(cached.optString("schedule", "正在同步"), "日程", "暂无日程") : "正在同步") : "";
        String syncedAt = cached.optString("synced_at", "");
        String status = loggedIn
                ? (current ? (syncedAt.isEmpty() ? "已同步" : syncedAt + " 已同步") + " · 轻点卡片看月历" : "等待今日数据 · 点 ↻ 刷新")
                : "登录后实时同步 Mac / 网页端";

        String termWeek = CalendarUtils.semesterWeekFull(today);

        Intent templateIntent = new Intent(context, CalendarWidget.class).setAction(ACTION_TOGGLE_TODO);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent toggleTemplate = PendingIntent.getBroadcast(context, 5, templateIntent, flags);

        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget);
            views.setTextViewText(R.id.title, CalendarUtils.shortDateHeading(today));
            if (termWeek.isEmpty()) {
                views.setViewVisibility(R.id.weekBadge, View.GONE);
            } else {
                views.setViewVisibility(R.id.weekBadge, View.VISIBLE);
                views.setTextViewText(R.id.weekBadge, termWeek);
            }
            views.setTextViewText(R.id.course, course);
            views.setTextViewText(R.id.schedule, schedule);
            views.setTextViewText(R.id.status, status);
            views.setViewVisibility(R.id.todoSection, loggedIn ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.scheduleSection, loggedIn ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.weekToggle, loggedIn ? View.VISIBLE : View.GONE);

            Intent todoService = new Intent(context, TodoListService.class);
            views.setRemoteAdapter(R.id.todoList, todoService);
            views.setPendingIntentTemplate(R.id.todoList, toggleTemplate);

            Intent open = new Intent(context, MainActivity.class);
            PendingIntent openIntent = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widgetRoot, openIntent);

            Intent add = new Intent(context, QuickAddActivity.class);
            PendingIntent addIntent = PendingIntent.getActivity(context, 3, add, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.add, addIntent);

            PendingIntent weekIntent = PendingIntent.getActivity(context, 4, new Intent(context, WeekActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.weekToggle, weekIntent);

            Intent refresh = new Intent(context, CalendarWidget.class).setAction(REFRESH);
            PendingIntent refreshIntent = PendingIntent.getBroadcast(context, 2, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.refresh, refreshIntent);

            manager.updateAppWidget(id, views);
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.todoList);
    }
}
