package com.calendarboard.widget;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for IslandPlanner covering Section 6b.6 cases. */
public class IslandPlannerTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    private static long ms(LocalDateTime dt) {
        return dt.atZone(ZONE).toInstant().toEpochMilli();
    }

    private static JSONObject item(String id, String type, LocalDate date, String title,
                                   String startTime, String endTime, boolean done) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("_row_id", id);
        obj.put("type", type);
        obj.put("date", date.toString());
        obj.put("title", title);
        if (startTime != null) obj.put("startTime", startTime);
        if (endTime != null) obj.put("endTime", endTime);
        if (done) obj.put("done", true);
        return obj;
    }

    @Test
    public void testBeforeWindowReturnsNoneWithWindowStart() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "高等数学", "08:00", "09:40", false);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(7, 10), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, p.kind);
        assertNull(p.item);
        assertEquals(ms(TODAY.atTime(7, 30)), p.nextChangeMs);
    }

    @Test
    public void testUpcomingCourseTwentyMinutesBefore() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "高等数学", "08:00", "09:40", false);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(7, 40), ZONE);
        assertEquals(IslandPlanner.Kind.UPCOMING, p.kind);
        assertEquals("20分后", p.shortText);
        assertTrue(p.shortText.length() <= 7);
        // 倒计时由系统走，下一次只需在开始时刻醒来
        assertEquals(ms(TODAY.atTime(8, 0)), p.nextChangeMs);
    }

    @Test
    public void testActiveCourseTicksOnFiveMinuteBoundary() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "高等数学", "08:00", "09:40", false);
        IslandPlanner.Plan mid = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(8, 21, 30), ZONE);
        assertEquals(IslandPlanner.Kind.ACTIVE, mid.kind);
        assertEquals(ms(TODAY.atTime(8, 25)), mid.nextChangeMs);

        IslandPlanner.Plan onBoundary = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(8, 25), ZONE);
        assertEquals(ms(TODAY.atTime(8, 30)), onBoundary.nextChangeMs);
    }

    @Test
    public void testActiveTickNeverSkipsStateChange() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "高等数学", "08:00", "09:38", false);
        JSONObject schedule = item("s1", "日程", TODAY, "班会", "09:02", "09:30", false);
        // 下课 9:38 早于下一个 5 分钟点 9:40
        IslandPlanner.Plan nearEnd = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(9, 36), ZONE);
        assertEquals(ms(TODAY.atTime(9, 38)), nearEnd.nextChangeMs);
        // 其他事项进入提前窗口（8:32）也要醒来
        IslandPlanner.Plan withOther = IslandPlanner.plan(
                Arrays.asList(course, schedule), TODAY.atTime(8, 31), ZONE);
        assertEquals(ms(TODAY.atTime(8, 32)), withOther.nextChangeMs);
    }

    @Test
    public void testCourseWithoutEndTimeDefaultsTo100Minutes() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "线性代数", "08:00", null, false);
        IslandPlanner.Plan active = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(8, 20), ZONE);
        assertEquals(IslandPlanner.Kind.ACTIVE, active.kind);
        assertEquals("剩80分", active.shortText);
        assertTrue(active.shortText.length() <= 7);
        assertEquals(ms(TODAY.atTime(9, 40)), active.targetMs);
        assertEquals(100, active.durationMinutes);

        IslandPlanner.Plan after = IslandPlanner.plan(
                Collections.singletonList(course), TODAY.atTime(9, 40), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, after.kind);
    }

    @Test
    public void testCourseTakesPriorityOverConcurrentSchedule() throws Exception {
        JSONObject course = item("c1", "课程", TODAY, "大学物理", "14:00", "15:40", false);
        JSONObject schedule = item("s1", "日程", TODAY, "班会", "14:30", "15:00", false);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Arrays.asList(schedule, course), TODAY.atTime(14, 35), ZONE);
        assertEquals(IslandPlanner.Kind.ACTIVE, p.kind);
        assertEquals("c1", p.item.getString("_row_id"));
        assertEquals(1, p.nextUp.size());
        assertEquals("s1", p.nextUp.get(0).getString("_row_id"));
        assertTrue(p.shortText.length() <= 7);
    }

    @Test
    public void testTimedTodoUpcomingThenDisappearsAtDueTime() throws Exception {
        JSONObject todo = item("t1", "待办", TODAY, "交实验报告", "16:00", null, false);
        IslandPlanner.Plan before = IslandPlanner.plan(
                Collections.singletonList(todo), TODAY.atTime(15, 45), ZONE);
        assertEquals(IslandPlanner.Kind.UPCOMING, before.kind);
        assertEquals("15分后", before.shortText);
        assertTrue(before.shortText.length() <= 7);

        IslandPlanner.Plan atDue = IslandPlanner.plan(
                Collections.singletonList(todo), TODAY.atTime(16, 0), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, atDue.kind);
    }

    @Test
    public void testCompletedTodoExcluded() throws Exception {
        JSONObject todo = item("t1", "待办", TODAY, "交实验报告", "16:00", null, true);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Collections.singletonList(todo), TODAY.atTime(15, 45), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, p.kind);
    }

    @Test
    public void testUntimedTodoNeverAppears() throws Exception {
        JSONObject todo = item("t1", "待办", TODAY, "背单词", null, null, false);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Collections.singletonList(todo), TODAY.atTime(10, 0), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, p.kind);
        assertEquals(0L, p.nextChangeMs);
    }

    @Test
    public void testNightSchedulesTomorrowFirstWindow() throws Exception {
        JSONObject tomorrowCourse = item("c2", "课程", TOMORROW, "数据结构", "08:00", "09:40", false);
        IslandPlanner.Plan p = IslandPlanner.plan(
                Collections.singletonList(tomorrowCourse), TODAY.atTime(22, 0), ZONE);
        assertEquals(IslandPlanner.Kind.NONE, p.kind);
        assertEquals(ms(TOMORROW.atTime(7, 30)), p.nextChangeMs);
    }

    @Test
    public void testShortTextWithinSevenCharsIncludingUnderOneMinute() throws Exception {
        JSONObject todo = item("t1", "待办", TODAY, "打卡", "16:00", null, false);
        IslandPlanner.Plan imminent = IslandPlanner.plan(
                Collections.singletonList(todo), TODAY.atTime(15, 59, 30), ZONE);
        assertEquals(IslandPlanner.Kind.UPCOMING, imminent.kind);
        assertEquals("马上", imminent.shortText);
        assertTrue(imminent.shortText.length() <= 7);
    }
}
