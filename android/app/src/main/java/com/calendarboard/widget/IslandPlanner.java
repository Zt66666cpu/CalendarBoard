package com.calendarboard.widget;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure planner that selects which course, schedule, or timed todo should appear on the
 * Live Updates island at a given instant, and when the island must next be recomputed.
 */
final class IslandPlanner {
    static final int LEAD_MIN = 30;
    static final int LESSON_MIN = 45;
    static final int BREAK_MIN = 10;
    static final int DEFAULT_COURSE_MIN = LESSON_MIN * 2 + BREAK_MIN;
    static final int DEFAULT_SCHEDULE_MIN = 30;

    /**
     * true：状态栏胶囊用系统倒计时（setUsesChronometer）自己走字，闹钟只在状态切换点和
     * 每 TICK_MIN 分钟刷新进度条时触发，避开 Doze 对 allow-while-idle 闹钟约 9 分钟一次的限流。
     * false：回退到旧做法（shortCriticalText 文字 + 每分钟一次闹钟）。
     */
    static final boolean CHIP_USES_CHRONOMETER = true;
    static final int TICK_MIN = 5;

    enum Kind { ACTIVE, UPCOMING, NONE }

    static final class Plan {
        final Kind kind;
        final JSONObject item;
        final long startMs;
        final long targetMs;
        final int durationMinutes;
        final int elapsedMinutes;
        final String shortText;
        final List<JSONObject> nextUp;
        final long nextChangeMs;

        Plan(Kind kind, JSONObject item, long startMs, long targetMs, int durationMinutes,
             int elapsedMinutes, String shortText, List<JSONObject> nextUp, long nextChangeMs) {
            this.kind = kind;
            this.item = item;
            this.startMs = startMs;
            this.targetMs = targetMs;
            this.durationMinutes = durationMinutes;
            this.elapsedMinutes = elapsedMinutes;
            this.shortText = shortText == null ? "" : shortText;
            this.nextUp = nextUp == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(nextUp);
            this.nextChangeMs = nextChangeMs;
        }
    }

    private static final class Candidate {
        final JSONObject item;
        final int typeRank; // 0 = course, 1 = schedule, 2 = todo
        final boolean canBeActive;
        final long windowStartMs;
        final long startMs;
        final long endMs;
        final int durationMinutes;

        Candidate(JSONObject item, int typeRank, boolean canBeActive,
                  long windowStartMs, long startMs, long endMs, int durationMinutes) {
            this.item = item;
            this.typeRank = typeRank;
            this.canBeActive = canBeActive;
            this.windowStartMs = windowStartMs;
            this.startMs = startMs;
            this.endMs = endMs;
            this.durationMinutes = durationMinutes;
        }
    }

    private IslandPlanner() { }

    static Plan plan(List<JSONObject> all, LocalDateTime now) {
        return plan(all, now, ZoneId.systemDefault());
    }

    static Plan plan(List<JSONObject> all, LocalDateTime now, ZoneId zone) {
        if (now == null) now = LocalDateTime.now();
        if (zone == null) zone = ZoneId.systemDefault();

        LocalDate todayDate = now.toLocalDate();
        String today = todayDate.toString();
        String tomorrow = todayDate.plusDays(1).toString();
        long nowMs = toMillis(now, zone);

        List<Candidate> active = new ArrayList<>();
        List<Candidate> upcoming = new ArrayList<>();
        long earliestFutureWindowMs = Long.MAX_VALUE;
        // 任意候选项最近的一个状态切换点（进入提前窗口 / 开始 / 结束）
        long nextEventMs = Long.MAX_VALUE;

        if (all != null) {
            for (JSONObject item : all) {
                Candidate c = toCandidate(item, today, tomorrow, zone);
                if (c == null) continue;

                if (c.windowStartMs > nowMs) nextEventMs = Math.min(nextEventMs, c.windowStartMs);
                if (c.startMs > nowMs) nextEventMs = Math.min(nextEventMs, c.startMs);
                if (c.canBeActive && c.endMs > nowMs) nextEventMs = Math.min(nextEventMs, c.endMs);

                if (c.canBeActive && c.startMs <= nowMs && nowMs < c.endMs) {
                    active.add(c);
                } else if (c.windowStartMs <= nowMs && nowMs < c.startMs) {
                    upcoming.add(c);
                } else if (c.windowStartMs > nowMs && c.windowStartMs < earliestFutureWindowMs) {
                    earliestFutureWindowMs = c.windowStartMs;
                }
            }
        }

        active.sort(Comparator
                .comparingInt((Candidate c) -> c.typeRank)
                .thenComparingLong(c -> c.endMs)
                .thenComparingLong(c -> c.startMs));

        upcoming.sort(Comparator
                .comparingLong((Candidate c) -> c.startMs)
                .thenComparingInt(c -> c.typeRank));

        LocalDateTime minuteFloor = now.truncatedTo(ChronoUnit.MINUTES);
        long nextMinuteMs = toMillis(minuteFloor.plusMinutes(1), zone);
        long nextTickMs = toMillis(
                minuteFloor.plusMinutes(TICK_MIN - (minuteFloor.getMinute() % TICK_MIN)), zone);

        if (!active.isEmpty()) {
            Candidate lead = active.get(0);
            List<JSONObject> nextUp = collectNextUp(active, 1, upcoming, 0);
            int elapsed = (int) Math.max(0L, (nowMs - lead.startMs) / 60_000L);
            long remainSec = Math.max(0L, (lead.endMs - nowMs) / 1000L);
            int remainMin = (int) Math.max(1L, (remainSec + 59L) / 60L);
            String shortText = "剩" + remainMin + "分";
            // 进行中：要定时刷新进度条，同时不能错过任何状态切换点
            long next = CHIP_USES_CHRONOMETER
                    ? Math.min(nextTickMs, nextEventMs)
                    : nextMinuteMs;
            return new Plan(Kind.ACTIVE, lead.item, lead.startMs, lead.endMs,
                    lead.durationMinutes, elapsed, shortText, nextUp, next);
        }

        if (!upcoming.isEmpty()) {
            Candidate lead = upcoming.get(0);
            List<JSONObject> nextUp = collectNextUp(active, 0, upcoming, 1);
            long remainSec = Math.max(0L, (lead.startMs - nowMs) / 1000L);
            String shortText;
            if (remainSec < 60L) {
                shortText = "马上";
            } else {
                int remainMin = (int) ((remainSec + 59L) / 60L);
                shortText = remainMin + "分后";
            }
            // 即将开始：倒计时由系统走，没有进度条，只需在下一个切换点（通常是开始时刻）醒来
            long next = CHIP_USES_CHRONOMETER ? nextEventMs : nextMinuteMs;
            return new Plan(Kind.UPCOMING, lead.item, lead.startMs, lead.startMs,
                    lead.durationMinutes, 0, shortText, nextUp, next);
        }

        long nextChange = earliestFutureWindowMs == Long.MAX_VALUE ? 0L : earliestFutureWindowMs;
        return new Plan(Kind.NONE, null, 0L, 0L, 0, 0, "", Collections.emptyList(), nextChange);
    }

    static String dismissKey(JSONObject item, LocalDate today) {
        if (item == null) return "";
        String id = item.optString("_row_id", "");
        if (id.isEmpty()) id = item.optString("id", "");
        if (id.isEmpty()) {
            id = item.optString("type", "") + "|" + item.optString("date", "")
                    + "|" + item.optString("startTime", item.optString("time", ""))
                    + "|" + item.optString("title", "");
        }
        return (today == null ? LocalDate.now().toString() : today.toString()) + "|" + id;
    }

    private static List<JSONObject> collectNextUp(List<Candidate> active, int activeStart,
                                                  List<Candidate> upcoming, int upcomingStart) {
        List<JSONObject> result = new ArrayList<>(2);
        for (int i = activeStart; i < active.size() && result.size() < 2; i++) {
            result.add(active.get(i).item);
        }
        for (int i = upcomingStart; i < upcoming.size() && result.size() < 2; i++) {
            result.add(upcoming.get(i).item);
        }
        return result;
    }

    private static Candidate toCandidate(JSONObject item, String today, String tomorrow,
                                         ZoneId zone) {
        if (item == null || Items.isMeta(item)) return null;
        if (Items.isArchived(item, today)) return null;

        String dateStr = item.optString("date", "");
        if (!today.equals(dateStr) && !tomorrow.equals(dateStr)) return null;

        String type = item.optString("type", "");
        int typeRank;
        boolean canBeActive;
        int defaultDuration;
        if ("课程".equals(type)) {
            typeRank = 0;
            canBeActive = true;
            defaultDuration = DEFAULT_COURSE_MIN;
        } else if ("日程".equals(type)) {
            typeRank = 1;
            canBeActive = true;
            defaultDuration = DEFAULT_SCHEDULE_MIN;
        } else if ("待办".equals(type)) {
            if (Items.isDone(item) || Items.isDropped(item)) return null;
            typeRank = 2;
            canBeActive = false;
            defaultDuration = 0;
        } else {
            return null;
        }

        Integer start = Items.itemStart(item);
        if (start == null) return null;

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception error) {
            return null;
        }

        LocalDateTime startAt = date.atTime(start / 60, start % 60);
        LocalDateTime windowStartAt = startAt.minusMinutes(LEAD_MIN);
        LocalDateTime endAt;
        int durationMinutes;
        if (canBeActive) {
            Integer end = Items.itemEnd(item);
            if (end != null && end > start) {
                endAt = date.atTime(end / 60, end % 60);
                durationMinutes = end - start;
            } else {
                endAt = startAt.plusMinutes(defaultDuration);
                durationMinutes = defaultDuration;
            }
        } else {
            endAt = startAt;
            durationMinutes = 0;
        }

        return new Candidate(
                item,
                typeRank,
                canBeActive,
                toMillis(windowStartAt, zone),
                toMillis(startAt, zone),
                toMillis(endAt, zone),
                durationMinutes);
    }

    private static long toMillis(LocalDateTime value, ZoneId zone) {
        return value.atZone(zone).toInstant().toEpochMilli();
    }
}
