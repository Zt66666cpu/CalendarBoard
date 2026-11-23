package com.calendarboard.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Compact 2x1 widget that always shows the current or next course. */
public final class NextClassWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, NextClassWidget.class));
        if (ids.length == 0) return;

        JSONObject cache = SessionStore.cache(context);
        boolean loggedIn = SessionStore.session(context) != null;
        LocalDate today = LocalDate.now();
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();

        String title;
        String meta;
        String place = "";
        if (!loggedIn) {
            title = "下一节课";
            meta = "登录后查看课程";
        } else {
            List<JSONObject> allItems = Notifier.allItems(cache);
            Items.NowNext nowNext = Items.nowNext(allItems, today.toString(), now);
            if (nowNext.current != null) {
                Items.CourseSlot course = nowNext.current;
                title = course.title;
                place = course.place;
                if (course.end != null) {
                    meta = "进行中 · " + Items.formatMinutes(course.start)
                            + "–" + Items.formatMinutes(course.end);
                } else {
                    meta = "进行中 · " + Items.formatMinutes(course.start) + " 开始";
                }
            } else if (nowNext.next != null) {
                Items.CourseSlot course = nowNext.next;
                title = course.title;
                place = course.place;
                meta = Items.formatMinutes(course.start) + " 开始";
            } else {
                title = "今天没课了";
                JSONObject tomorrow = earliestCourse(allItems, today.plusDays(1).toString());
                if (tomorrow == null) {
                    meta = "明天也暂无课程";
                } else {
                    Integer start = Items.itemStart(tomorrow);
                    meta = "明天 " + Items.formatMinutes(start) + " · "
                            + tomorrow.optString("title", "课程");
                    place = tomorrow.optString("place", "");
                    if (place.isEmpty()) place = tomorrow.optString("room", "");
                }
            }
        }

        PendingIntent openIntent = PendingIntent.getActivity(context, 41,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.next_class_widget);
            views.setTextViewText(R.id.nextTitle, title);
            views.setTextViewText(R.id.nextMeta, meta);
            if (place.isEmpty()) {
                views.setViewVisibility(R.id.nextPlace, View.GONE);
            } else {
                views.setViewVisibility(R.id.nextPlace, View.VISIBLE);
                views.setTextViewText(R.id.nextPlace, place);
            }
            views.setOnClickPendingIntent(R.id.nextClassRoot, openIntent);
            manager.updateAppWidget(id, views);
        }
    }

    private static JSONObject earliestCourse(List<JSONObject> allItems, String date) {
        List<JSONObject> courses = new ArrayList<>();
        for (JSONObject item : allItems) {
            if (item == null || !"课程".equals(item.optString("type", ""))) continue;
            if (!date.equals(item.optString("date", ""))) continue;
            if (Items.itemStart(item) == null) continue;
            courses.add(item);
        }
        if (courses.isEmpty()) return null;
        courses.sort(Items.BY_TIME);
        return courses.get(0);
    }
}
