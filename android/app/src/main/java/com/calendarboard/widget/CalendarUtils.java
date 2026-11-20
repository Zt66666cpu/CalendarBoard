package com.calendarboard.widget;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

final class CalendarUtils {
    private static final LocalDate TERM_START = LocalDate.of(2026, 8, 31); // 2026秋 第1周 周一
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private CalendarUtils() { }

    static String weekdayLabel(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1];
    }

    static String shortDateHeading(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日 · " + weekdayLabel(date);
    }

    /** Returns e.g. "第12周" or "" if outside the 20-week semester. */
    static String semesterWeekShort(LocalDate date) {
        long days = ChronoUnit.DAYS.between(TERM_START, date);
        if (days < 0) return "";
        long week = days / 7 + 1;
        if (week >= 1 && week <= 20) return "第" + week + "周";
        return "";
    }

    /** Returns e.g. "2026秋 第12周" or "" if outside semester. */
    static String semesterWeekFull(LocalDate date) {
        String s = semesterWeekShort(date);
        return s.isEmpty() ? "" : "2026秋 " + s;
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
