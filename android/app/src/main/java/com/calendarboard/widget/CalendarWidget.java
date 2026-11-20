package com.calendarboard.widget;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalDate;

public final class CalendarWidget extends AppWidgetProvider {
    static final String REFRESH = "com.calendarboard.widget.REFRESH";
    private static final int IMMEDIATE_JOB = 101;
    private static final int PERIODIC_JOB = 102;

    private static String widgetBody(String value, String heading, String emptyText) {
        String body = value.startsWith(heading + "：") ? value.substring(heading.length() + 1) : value;
        if (body.isEmpty() || "无".equals(body)) return emptyText;
        return "· " + body.replace("\n", "\n· ");
    }

    @Override public void onEnabled(Context context) { schedulePeriodic(context); }

    @Override public void onDisabled(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        jobs.cancel(PERIODIC_JOB);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        schedulePeriodic(context);
        scheduleNow(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) {
            renderAll(context);
            scheduleNow(context);
        }
    }

    static void scheduleNow(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job = new JobInfo.Builder(IMMEDIATE_JOB, new ComponentName(context, SyncService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(1000)
                .build();
        jobs.schedule(job);
    }

    private static void schedulePeriodic(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs.getPendingJob(PERIODIC_JOB) != null) return;
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, SyncService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60 * 1000L)
                .build();
        jobs.schedule(job);
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, CalendarWidget.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return;

        JSONObject cached = SessionStore.cache(context);
        boolean loggedIn = SessionStore.session(context) != null;
        LocalDate today = LocalDate.now();
        boolean current = today.toString().equals(cached.optString("date"));

        String course = loggedIn ? (current ? widgetBody(cached.optString("course", "正在同步"), "课程", "今天没有课程") : "正在同步") : "轻点卡片登录账号";
        String todo = loggedIn ? (current ? widgetBody(cached.optString("todo", "正在同步"), "待办", "暂无待办") : "正在同步") : "";
        String schedule = loggedIn ? (current ? widgetBody(cached.optString("schedule", "正在同步"), "日程", "暂无日程") : "正在同步") : "";
        String syncedAt = cached.optString("synced_at", "");
        String status = loggedIn
                ? (current ? (syncedAt.isEmpty() ? "已同步" : syncedAt + " 已同步") + " · 轻点卡片看月历" : "等待今日数据 · 点 ↻ 刷新")
                : "登录后实时同步 Mac / 网页端";

        String termWeek = CalendarUtils.semesterWeekFull(today);

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
            views.setTextViewText(R.id.todo, todo);
            views.setTextViewText(R.id.schedule, schedule);
            views.setTextViewText(R.id.status, status);
            views.setViewVisibility(R.id.todoSection, loggedIn ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.scheduleSection, loggedIn ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.weekToggle, loggedIn ? View.VISIBLE : View.GONE);

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
    }
}
