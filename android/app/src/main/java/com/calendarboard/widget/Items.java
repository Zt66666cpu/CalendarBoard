package com.calendarboard.widget;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rule layer for CalendarBoard. This class mirrors CBCore in web/core.js and must not depend
 * on Android UI classes.
 */
final class Items {

    static final int SUPPORTED_SCHEMA = 2;
    static final Comparator<JSONObject> BY_TIME = Items::byTime;

    private static final Pattern CLOCK_RE =
            Pattern.compile("^\\s*(\\d{1,2})[:：](\\d{2})\\s*$");
    private static final Pattern LEGACY_TIME_RE =
            Pattern.compile("^\\s*(\\d{1,2})[:：](\\d{2})\\s*(?:[-–—~～至到]\\s*(\\d{1,2})[:：](\\d{2}))?\\s*$");
    private static final Pattern ISO_DATE_RE =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    private static final DateTimeFormatter NOW_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    private Items() { }

    static String nowIso() {
        return OffsetDateTime.now().format(NOW_FORMAT);
    }

    // ------------------------------------------------------------------
    // Time parsing and ordering (implementation plan 4.5)
    // ------------------------------------------------------------------

    static Integer parseMinutes(String str) {
        Matcher matcher = CLOCK_RE.matcher(str == null ? "" : str);
        if (!matcher.matches()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return hour * 60 + minute;
    }

    static String formatMinutes(Integer mins) {
        if (mins == null || mins < 0 || mins >= 1440) return "";
        return String.format(Locale.ROOT, "%02d:%02d", mins / 60, mins % 60);
    }

    /**
     * Numeric overload kept for parity with JavaScript: Number.isInteger rejects 9.5 before the
     * range check, while the Integer overload can only receive whole numbers.
     */
    static String formatMinutes(Number mins) {
        if (mins == null) return "";
        double value = mins.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) return "";
        if (value < 0 || value >= 1440) return "";
        return formatMinutes(Integer.valueOf((int) value));
    }

    static Integer itemStart(JSONObject p) {
        if (p == null) return null;
        Integer parsed = parseMinutes(text(p, "startTime"));
        if (parsed != null) return parsed;
        String legacy = text(p, "time");
        if (legacy.isEmpty()) return null;
        Matcher matcher = LEGACY_TIME_RE.matcher(legacy);
        if (!matcher.matches()) return null;
        return parseMinutes(matcher.group(1) + ":" + matcher.group(2));
    }

    static Integer itemEnd(JSONObject p) {
        Integer start = itemStart(p);
        if (start == null) return null;
        Integer end = parseMinutes(text(p, "endTime"));
        if (end == null) {
            String legacy = text(p, "time");
            if (!legacy.isEmpty()) {
                Matcher matcher = LEGACY_TIME_RE.matcher(legacy);
                if (matcher.matches() && matcher.group(3) != null) {
                    end = parseMinutes(matcher.group(3) + ":" + matcher.group(4));
                }
            }
        }
        if (end == null || end <= start) return null;
        return end;
    }

    static String buildTime(String startTime, String endTime) {
        Integer start = parseMinutes(startTime);
        if (start == null) return "";
        Integer end = parseMinutes(endTime);
        return end != null && end > start
                ? formatMinutes(start) + "-" + formatMinutes(end)
                : formatMinutes(start);
    }

    static int byTime(JSONObject a, JSONObject b) {
        Integer startA = itemStart(a);
        Integer startB = itemStart(b);
        if (startA != null && startB == null) return -1;
        if (startA == null && startB != null) return 1;
        if (startA != null && startB != null && !startA.equals(startB)) {
            return Integer.compare(startA, startB);
        }

        int titleOrder = text(a, "title").compareTo(text(b, "title"));
        if (titleOrder != 0) return titleOrder;

        return rowId(a).compareTo(rowId(b));
    }

    // ------------------------------------------------------------------
    // Status rules (implementation plan 4.4)
    // ------------------------------------------------------------------

    static boolean isMeta(JSONObject p) {
        return p != null && "meta".equals(text(p, "type"));
    }

    static boolean isDone(JSONObject p) {
        return p != null && "待办".equals(text(p, "type")) && Boolean.TRUE.equals(p.opt("done"));
    }

    static boolean isDropped(JSONObject p) {
        return p != null && "待办".equals(text(p, "type")) && "dropped".equals(text(p, "status"));
    }

    static boolean isArchived(JSONObject p, String today) {
        if (p == null || isMeta(p)) return false;
        if (isDone(p) || isDropped(p)) return true;
        return "日程".equals(text(p, "type"))
                && hasText(p, "date")
                && today != null
                && text(p, "date").compareTo(today) < 0;
    }

    static boolean isPending(JSONObject p, String today) {
        return p != null
                && "待办".equals(text(p, "type"))
                && !isDone(p)
                && !isDropped(p)
                && hasText(p, "date")
                && today != null
                && text(p, "date").compareTo(today) < 0;
    }

    // ------------------------------------------------------------------
    // View data (implementation plan P1.1)
    // ------------------------------------------------------------------

    static DayItemsResult dayItems(List<JSONObject> items, String iso, String today) {
        return dayItems(items, iso, today, Collections.<String>emptySet());
    }

    static DayItemsResult dayItems(List<JSONObject> items, String iso, String today,
                                   Set<String> recentDoneIds) {
        List<JSONObject> rows = datedRows(items);
        boolean past = iso != null && today != null && iso.compareTo(today) < 0;

        List<JSONObject> courses = new ArrayList<>();
        int archivedCount = 0;
        for (JSONObject p : rows) {
            if (!isoEquals(p, iso)) continue;
            if ("课程".equals(text(p, "type"))) courses.add(p);
            if (isArchived(p, today) && !isRecent(p, recentDoneIds)) archivedCount++;
        }
        courses.sort(BY_TIME);

        List<JSONObject> schedules = new ArrayList<>();
        List<JSONObject> todos = new ArrayList<>();
        List<JSONObject> pending = new ArrayList<>();
        if (!past) {
            for (JSONObject p : rows) {
                if (!isoEquals(p, iso)) continue;
                String type = text(p, "type");
                if ("日程".equals(type) && !isArchived(p, today)) {
                    schedules.add(p);
                } else if ("待办".equals(type)
                        && (!isArchived(p, today) || (isDone(p) && isRecent(p, recentDoneIds)))) {
                    todos.add(p);
                }
            }
            schedules.sort(BY_TIME);
            todos.sort(BY_TIME);

            if (iso != null && iso.equals(today)) {
                for (JSONObject p : rows) {
                    if ("待办".equals(text(p, "type"))
                            && text(p, "date").compareTo(today) < 0
                            && !isDropped(p)
                            && (!isDone(p) || isRecent(p, recentDoneIds))) {
                        pending.add(p);
                    }
                }
                pending.sort((a, b) -> {
                    int dateOrder = text(a, "date").compareTo(text(b, "date"));
                    if (dateOrder != 0) return dateOrder;
                    return byTime(a, b);
                });
            }
        }

        return new DayItemsResult(past, courses, schedules, todos, pending, archivedCount);
    }

    static DayDots dayDots(List<JSONObject> items, String iso, String today) {
        List<JSONObject> rows = datedRows(items);
        boolean course = false;
        boolean todo = false;
        boolean schedule = false;
        boolean notPast = iso != null && today != null && iso.compareTo(today) >= 0;
        boolean isToday = iso != null && iso.equals(today);

        for (JSONObject p : rows) {
            String type = text(p, "type");
            if (isoEquals(p, iso) && "课程".equals(type)) course = true;
            if (notPast && isoEquals(p, iso) && "待办".equals(type) && !isArchived(p, today)) {
                todo = true;
            }
            if (isToday && isPending(p, today)) todo = true;
            if (notPast && isoEquals(p, iso) && "日程".equals(type)) schedule = true;
        }
        return new DayDots(course, todo, schedule);
    }

    static List<WeekDay> weekAhead(List<JSONObject> items, String today) {
        List<JSONObject> rows = items == null ? Collections.<JSONObject>emptyList() : items;
        List<WeekDay> result = new ArrayList<>();
        for (int offset = 1; offset <= 7; offset++) {
            String iso = addDaysIso(today, offset);
            List<JSONObject> courses = new ArrayList<>();
            List<JSONObject> dayRows = new ArrayList<>();
            for (JSONObject p : rows) {
                if (p == null || isMeta(p) || !iso.equals(text(p, "date"))) continue;
                String type = text(p, "type");
                if ("课程".equals(type)) courses.add(p);
                if ("日程".equals(type) || ("待办".equals(type) && !isArchived(p, today))) {
                    dayRows.add(p);
                }
            }
            courses.sort(BY_TIME);
            dayRows.sort(BY_TIME);
            Integer firstCourseStart = null;
            for (JSONObject course : courses) {
                Integer start = itemStart(course);
                if (start != null) {
                    firstCourseStart = start;
                    break;
                }
            }
            result.add(new WeekDay(iso, courses.size(), firstCourseStart, dayRows));
        }
        return result;
    }

    static List<ArchiveGroup> archiveList(List<JSONObject> items, String today, String filter) {
        String effectiveFilter = filter == null ? "all" : filter;
        Map<String, ArchiveGroup> groups = new LinkedHashMap<>();
        if (items != null) {
            for (JSONObject p : items) {
                if (p == null || !isArchived(p, today)) continue;
                String type = text(p, "type");
                if (("待办".equals(effectiveFilter) || "日程".equals(effectiveFilter))
                        && !effectiveFilter.equals(type)) {
                    continue;
                }

                String state;
                String at;
                String iso;
                if (isDone(p)) {
                    state = "done";
                    at = textOrNull(p, "completedAt");
                    iso = at != null ? isoFromTimestamp(at) : text(p, "date");
                } else if (isDropped(p)) {
                    state = "dropped";
                    at = textOrNull(p, "droppedAt");
                    iso = at != null ? isoFromTimestamp(at) : text(p, "date");
                } else {
                    state = "ended";
                    at = null;
                    iso = text(p, "date");
                }
                if (iso == null) iso = "";

                ArchiveGroup group = groups.get(iso);
                if (group == null) {
                    group = new ArchiveGroup(iso);
                    groups.put(iso, group);
                }
                group.entries.add(new ArchiveEntry(p, state, at));
            }
        }

        List<ArchiveGroup> result = new ArrayList<>(groups.values());
        result.sort((a, b) -> {
            int order = a.iso.compareTo(b.iso);
            if (order == 0) return 0;
            return order > 0 ? -1 : 1;
        });

        for (ArchiveGroup group : result) {
            group.entries.sort((a, b) -> {
                if (a.at != null && b.at == null) return -1;
                if (a.at == null && b.at != null) return 1;
                if (a.at != null && b.at != null) {
                    Instant aInstant = parseTimestamp(a.at);
                    Instant bInstant = parseTimestamp(b.at);
                    if (aInstant != null && bInstant != null) {
                        int timestampOrder = bInstant.compareTo(aInstant);
                        if (timestampOrder != 0) return timestampOrder;
                    }
                }
                return byTime(a.item, b.item);
            });
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Review, mutations, now/next and settings row
    // ------------------------------------------------------------------

    static int reviewTriggerMinutes(List<JSONObject> items, String today) {
        Integer latest = null;
        if (items != null) {
            for (JSONObject p : items) {
                if (p == null || !"待办".equals(text(p, "type"))) continue;
                if (!isoEquals(p, today) || isDone(p) || isDropped(p)) continue;
                Integer start = itemStart(p);
                if (start == null) continue;
                Integer end = itemEnd(p);
                int effectiveEnd = end == null ? start : end;
                latest = latest == null ? Integer.valueOf(effectiveEnd) : Integer.valueOf(Math.max(latest, effectiveEnd));
            }
        }
        return latest != null ? Math.max(latest, 1080) : 1080;
    }

    static List<JSONObject> reviewList(List<JSONObject> items, String today, Integer nowMinutes) {
        int trigger = reviewTriggerMinutes(items, today);
        boolean reached = nowMinutes != null && nowMinutes >= trigger;
        List<JSONObject> result = new ArrayList<>();
        if (items != null) {
            for (JSONObject p : items) {
                if (p == null || !"待办".equals(text(p, "type"))) continue;
                if (isDone(p) || isDropped(p) || !hasText(p, "date")) continue;
                String date = text(p, "date");
                boolean include = reached ? date.compareTo(today) <= 0 : date.compareTo(today) < 0;
                if (include) result.add(p);
            }
        }
        result.sort((a, b) -> {
            int dateOrder = text(a, "date").compareTo(text(b, "date"));
            if (dateOrder != 0) return dateOrder;
            return byTime(a, b);
        });
        return result;
    }

    static JSONObject applyDone(JSONObject p, boolean done) {
        return applyDone(p, done, nowIso());
    }

    static JSONObject applyDone(JSONObject p, boolean done, String nowStr) {
        JSONObject copy = copy(p);
        copy.remove("id");
        copy.remove("_row_id");
        put(copy, "done", done);
        if (done) {
            put(copy, "completedAt", nowStr);
        } else {
            copy.remove("completedAt");
        }
        return copy;
    }

    static JSONObject applyPostpone(JSONObject p, String targetIso) {
        JSONObject copy = copy(p);
        copy.remove("id");
        copy.remove("_row_id");
        if (!isTruthy(copy.opt("originalDate"))) {
            String originalDate = text(p, "date");
            if (originalDate.isEmpty()) {
                copy.remove("originalDate");
            } else {
                put(copy, "originalDate", originalDate);
            }
        }
        put(copy, "date", targetIso);
        Double rawCount = numberValue(copy.opt("postponeCount"));
        int count = rawCount == null || rawCount.isNaN() || rawCount.isInfinite()
                ? 0
                : rawCount.intValue();
        put(copy, "postponeCount", count + 1);
        return copy;
    }

    static JSONObject applyDrop(JSONObject p) {
        return applyDrop(p, nowIso());
    }

    static JSONObject applyDrop(JSONObject p, String nowStr) {
        JSONObject copy = copy(p);
        copy.remove("id");
        copy.remove("_row_id");
        put(copy, "status", "dropped");
        put(copy, "droppedAt", nowStr);
        return copy;
    }

    static JSONObject applyRestore(JSONObject p) {
        JSONObject copy = copy(p);
        copy.remove("id");
        copy.remove("_row_id");
        copy.remove("status");
        copy.remove("droppedAt");
        if (isTruthy(copy.opt("done"))) {
            put(copy, "done", false);
            copy.remove("completedAt");
        }
        return copy;
    }

    static NowNext nowNext(List<JSONObject> items, String today, int nowMinutes) {
        List<JSONObject> courseRows = new ArrayList<>();
        if (items != null) {
            for (JSONObject p : items) {
                if (p == null || !"课程".equals(text(p, "type")) || !isoEquals(p, today)) continue;
                if (itemStart(p) != null) courseRows.add(p);
            }
        }
        courseRows.sort(BY_TIME);

        List<CourseSlot> courses = new ArrayList<>();
        for (JSONObject p : courseRows) {
            String id = text(p, "id");
            if (id.isEmpty()) id = text(p, "_row_id");
            String place = text(p, "place");
            if (place.isEmpty()) place = text(p, "room");
            courses.add(new CourseSlot(id, text(p, "title"), place, itemStart(p), itemEnd(p)));
        }

        CourseSlot current = null;
        for (CourseSlot course : courses) {
            if (course.end != null && course.start <= nowMinutes && nowMinutes < course.end) {
                current = course;
                break;
            }
        }
        CourseSlot next = null;
        for (CourseSlot course : courses) {
            if (course.start > nowMinutes) {
                next = course;
                break;
            }
        }
        Integer freeUntil = current == null && next != null ? next.start : null;
        return new NowNext(current, next, freeUntil);
    }

    static String metaId(String userId) {
        return String.valueOf(userId == null ? "" : userId)
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    static JSONObject defaultMeta() {
        JSONObject meta = new JSONObject();
        put(meta, "type", "meta");
        put(meta, "schemaVersion", SUPPORTED_SCHEMA);
        put(meta, "termName", "2026秋");
        put(meta, "termStart", "2026-08-31");
        put(meta, "termWeeks", 20);
        return meta;
    }

    static String termWeekLabel(JSONObject meta, LocalDate date) {
        if (date == null) return "";
        String termStartText = text(meta, "termStart");
        if (termStartText.isEmpty()) termStartText = "2026-08-31";
        LocalDate start = parseIsoDate(termStartText);
        if (start == null) return "";

        int totalWeeks = 20;
        Double rawWeeks = numberValue(meta == null ? null : meta.opt("termWeeks"));
        if (rawWeeks != null
                && !rawWeeks.isNaN()
                && !rawWeeks.isInfinite()
                && rawWeeks == Math.rint(rawWeeks)
                && rawWeeks > 0) {
            totalWeeks = rawWeeks.intValue();
        }

        long dayOffset = ChronoUnit.DAYS.between(start, date);
        if (dayOffset < 0) return "";
        long week = dayOffset / 7 + 1;
        if (week < 1 || week > totalWeeks) return "";
        return "第" + week + "周";
    }

    static String termWeekLabel(JSONObject meta, String iso) {
        return termWeekLabel(meta, parseIsoDate(iso));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<JSONObject> datedRows(List<JSONObject> items) {
        List<JSONObject> rows = new ArrayList<>();
        if (items != null) {
            for (JSONObject p : items) {
                if (p != null && !isMeta(p) && hasText(p, "date")) rows.add(p);
            }
        }
        return rows;
    }

    private static boolean isoEquals(JSONObject p, String iso) {
        return p != null && iso != null && iso.equals(text(p, "date"));
    }

    private static boolean isRecent(JSONObject p, Set<String> recentDoneIds) {
        if (p == null || recentDoneIds == null) return false;
        String key = itemKey(p);
        return key != null && recentDoneIds.contains(key);
    }

    private static String itemKey(JSONObject p) {
        String id = text(p, "id");
        if (!id.isEmpty()) return id;
        String rowId = text(p, "_row_id");
        return rowId.isEmpty() ? null : rowId;
    }

    private static String rowId(JSONObject p) {
        String row = text(p, "_row_id");
        if (!row.isEmpty()) return row;
        return text(p, "id");
    }

    private static String text(JSONObject p, String key) {
        if (p == null) return "";
        Object value = p.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return "";
        return String.valueOf(value);
    }

    private static String textOrNull(JSONObject p, String key) {
        String value = text(p, key);
        return value.isEmpty() ? null : value;
    }

    private static boolean hasText(JSONObject p, String key) {
        return !text(p, key).isEmpty();
    }

    private static boolean isTruthy(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return false;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        return true;
    }

    private static Double numberValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return null;
        if (value instanceof Number) return Double.valueOf(((Number) value).doubleValue());
        if (value instanceof Boolean) return Double.valueOf(((Boolean) value) ? 1 : 0);
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            return Double.valueOf(Double.NaN);
        }
    }

    private static JSONObject copy(JSONObject p) {
        if (p == null) throw new IllegalArgumentException("事项不能为空");
        try {
            return new JSONObject(p.toString());
        } catch (JSONException error) {
            throw new IllegalStateException("复制事项失败", error);
        }
    }

    private static JSONObject put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
            return target;
        } catch (JSONException error) {
            throw new IllegalStateException("写入字段失败：" + key, error);
        }
    }

    private static String addDaysIso(String iso, int days) {
        return LocalDate.parse(iso).plusDays(days).toString();
    }

    private static LocalDate parseIsoDate(String value) {
        if (value == null) return null;
        Matcher matcher = ISO_DATE_RE.matcher(value);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String isoFromTimestamp(String value) {
        Instant instant = parseTimestamp(value);
        if (instant == null) return null;
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try the next accepted representation.
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try the next accepted representation.
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try the next accepted representation.
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Result value types
    // ------------------------------------------------------------------

    static final class DayItemsResult {
        final boolean past;
        final List<JSONObject> courses;
        final List<JSONObject> schedules;
        final List<JSONObject> todos;
        final List<JSONObject> pending;
        final int archivedCount;

        DayItemsResult(boolean past, List<JSONObject> courses, List<JSONObject> schedules,
                       List<JSONObject> todos, List<JSONObject> pending, int archivedCount) {
            this.past = past;
            this.courses = courses;
            this.schedules = schedules;
            this.todos = todos;
            this.pending = pending;
            this.archivedCount = archivedCount;
        }
    }

    static final class DayDots {
        final boolean course;
        final boolean todo;
        final boolean schedule;

        DayDots(boolean course, boolean todo, boolean schedule) {
            this.course = course;
            this.todo = todo;
            this.schedule = schedule;
        }
    }

    static final class WeekDay {
        final String iso;
        final int courseCount;
        final Integer firstCourseStart;
        final List<JSONObject> items;

        WeekDay(String iso, int courseCount, Integer firstCourseStart, List<JSONObject> items) {
            this.iso = iso;
            this.courseCount = courseCount;
            this.firstCourseStart = firstCourseStart;
            this.items = items;
        }
    }

    static final class ArchiveGroup {
        final String iso;
        final List<ArchiveEntry> entries = new ArrayList<>();

        ArchiveGroup(String iso) {
            this.iso = iso;
        }
    }

    static final class ArchiveEntry {
        final JSONObject item;
        final String state;
        final String at;

        ArchiveEntry(JSONObject item, String state, String at) {
            this.item = item;
            this.state = state;
            this.at = at;
        }
    }

    static final class CourseSlot {
        final String id;
        final String title;
        final String place;
        final Integer start;
        final Integer end;

        CourseSlot(String id, String title, String place, Integer start, Integer end) {
            this.id = id;
            this.title = title;
            this.place = place;
            this.start = start;
            this.end = end;
        }
    }

    static final class NowNext {
        final CourseSlot current;
        final CourseSlot next;
        final Integer freeUntil;

        NowNext(CourseSlot current, CourseSlot next, Integer freeUntil) {
            this.current = current;
            this.next = next;
            this.freeUntil = freeUntil;
        }
    }
}
