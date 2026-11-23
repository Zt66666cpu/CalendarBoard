package com.calendarboard.widget;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;

final class CalendarUtils {
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private CalendarUtils() { }

    static String weekdayLabel(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1];
    }

    static String shortDateHeading(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日 · " + weekdayLabel(date);
    }

    private static JSONObject meta(Context context) {
        if (context != null) {
            JSONObject cached = SessionStore.cache(context).optJSONObject("meta");
            if (cached != null) return cached;
        }
        return Items.defaultMeta();
    }

    static String semesterWeekShort(Context context, LocalDate date) {
        return semesterWeekShort(meta(context), date);
    }

    static String semesterWeekShort(JSONObject meta, LocalDate date) {
        return Items.termWeekLabel(meta == null ? Items.defaultMeta() : meta, date);
    }

    /** Fallback for callers without a context; uses the built-in default semester. */
    static String semesterWeekShort(LocalDate date) {
        return semesterWeekShort(Items.defaultMeta(), date);
    }

    static String semesterWeekFull(Context context, LocalDate date) {
        return semesterWeekFull(meta(context), date);
    }

    static String semesterWeekFull(JSONObject meta, LocalDate date) {
        JSONObject settings = meta == null ? Items.defaultMeta() : meta;
        String week = Items.termWeekLabel(settings, date);
        if (week.isEmpty()) return "";
        String name = settings.optString("termName", "2026秋").trim();
        if (name.isEmpty()) name = "2026秋";
        return name + " " + week;
    }

    /** Fallback for callers without a context; uses the built-in default semester. */
    static String semesterWeekFull(LocalDate date) {
        return semesterWeekFull(Items.defaultMeta(), date);
    }

    /** Concise solar holiday label for calendar cells. */
    static String holidayLabel(LocalDate date) {
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        if (m == 1 && d == 1) return "元旦";
        if (m == 2 && d == 14) return "情人节";
        if (m == 3 && d == 8) return "妇女节";
        if (m == 4 && d == 5) return "清明";
        if (m == 5 && d == 1) return "劳动节";
        if (m == 5 && d == 4) return "青年节";
        if (m == 6 && d == 1) return "儿童节";
        if (m == 9 && d == 10) return "教师节";
        if (m == 10 && d == 1) return "国庆";
        if (m == 12 && d == 25) return "圣诞";
        return "";
    }
}
