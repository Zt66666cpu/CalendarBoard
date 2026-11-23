package com.calendarboard.widget;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.calendarboard.widget.Items.ArchiveEntry;
import com.calendarboard.widget.Items.ArchiveGroup;
import com.calendarboard.widget.Items.CourseSlot;
import com.calendarboard.widget.Items.DayDots;
import com.calendarboard.widget.Items.DayItemsResult;
import com.calendarboard.widget.Items.NowNext;
import com.calendarboard.widget.Items.WeekDay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Cross-language consistency tests: every expectation in shared/rules-testcases.json must match
 * the Java rule layer.
 */
public class ItemsTest {

    private static JSONObject cases;

    @BeforeClass
    public static void loadCases() throws Exception {
        cases = readCases();
    }

    @Test
    public void timeParsingMatchesCases() throws Exception {
        JSONArray entries = cases.getJSONArray("timeParsing");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String input = entry.getString("input");
            Object expected = entry.get("expected");
            Integer actual = Items.parseMinutes(input);
            if (expected == null || JSONObject.NULL.equals(expected)) {
                assertNull("timeParsing[" + i + "] input=" + input, actual);
            } else {
                assertEquals("timeParsing[" + i + "] input=" + input,
                        Integer.valueOf(((Number) expected).intValue()), actual);
            }
        }
    }

    @Test
    public void formattingMatchesCases() throws Exception {
        JSONArray entries = cases.getJSONArray("formatting");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Object input = entry.opt("input");
            String expected = entry.getString("expected");
            String actual;
            if (input == null || JSONObject.NULL.equals(input)) {
                actual = Items.formatMinutes((Integer) null);
            } else {
                Number number = (Number) input;
                double value = number.doubleValue();
                if (value == Math.rint(value)) {
                    actual = Items.formatMinutes(Integer.valueOf((int) value));
                } else {
                    actual = Items.formatMinutes(number);
                }
            }
            assertEquals("formatting[" + i + "] input=" + input, expected, actual);
        }
    }

    @Test
    public void sortingMatchesCases() throws Exception {
        JSONArray entries = cases.getJSONArray("sorting");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            List<JSONObject> sorted = new ArrayList<>(toList(entry.getJSONArray("items")));
            sorted.sort(Items.BY_TIME);
            assertIds(entry.getJSONArray("expectedIds"), sorted);
        }
    }

    @Test
    public void legacyTimeMatchesCases() throws Exception {
        JSONArray entries = cases.getJSONArray("legacyTime");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = entry.getString("name");
            JSONObject item = entry.getJSONObject("item");

            Integer expectedStart = integerOrNull(entry, "start");
            Integer expectedEnd = integerOrNull(entry, "end");
            Integer actualStart = Items.itemStart(item);
            Integer actualEnd = Items.itemEnd(item);

            assertEquals(name + " start", expectedStart, actualStart);
            assertEquals(name + " end", expectedEnd, actualEnd);
            assertEquals(name + " build", entry.getString("build"),
                    Items.buildTime(Items.formatMinutes(actualStart), Items.formatMinutes(actualEnd)));
        }

        assertEquals("09:05-10:30", Items.buildTime("9:05", "10:30"));
        assertEquals("10:00", Items.buildTime("10:00", null));
        assertEquals("", Items.buildTime("", "10:00"));
        assertEquals("09:00", Items.buildTime("9:00", "9:00"));
    }

    @Test
    public void statusChecksMatchCases() throws Exception {
        JSONObject checks = cases.getJSONObject("statusChecks");
        String today = cases.getString("today");
        Map<String, JSONObject> byId = byId(dataset());

        for (String id : stringList(checks.getJSONArray("done"))) {
            assertTrue("isDone " + id, Items.isDone(item(byId, id)));
        }
        for (String id : stringList(checks.getJSONArray("dropped"))) {
            assertTrue("isDropped " + id, Items.isDropped(item(byId, id)));
        }
        for (String id : stringList(checks.getJSONArray("pending"))) {
            assertTrue("isPending " + id, Items.isPending(item(byId, id), today));
        }
        for (String id : stringList(checks.getJSONArray("archived"))) {
            assertTrue("isArchived " + id, Items.isArchived(item(byId, id), today));
        }
        for (String id : stringList(checks.getJSONArray("noArchive"))) {
            assertFalse("isArchived " + id, Items.isArchived(item(byId, id), today));
        }
        assertTrue("isMeta", Items.isMeta(item(byId, "meta-row")));
        assertFalse("isMeta todo", Items.isMeta(item(byId, "todo-today")));
    }

    @Test
    public void dayItemsMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("dayItems");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Set<String> recent = new LinkedHashSet<>(stringList(entry.getJSONArray("recentDoneIds")));
            DayItemsResult result = Items.dayItems(dataset(), entry.getString("iso"), today, recent);
            JSONObject expected = entry.getJSONObject("expected");

            assertEquals(entry.getString("name") + " past", expected.getBoolean("past"), result.past);
            assertIds(expected.getJSONArray("courseIds"), result.courses);
            assertIds(expected.getJSONArray("scheduleIds"), result.schedules);
            assertIds(expected.getJSONArray("todoIds"), result.todos);
            assertIds(expected.getJSONArray("pendingIds"), result.pending);
            assertEquals(entry.getString("name") + " archivedCount",
                    expected.getInt("archivedCount"), result.archivedCount);
        }
    }

    @Test
    public void dayDotsMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("dayDots");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            DayDots dots = Items.dayDots(dataset(), entry.getString("iso"), today);
            JSONObject expected = entry.getJSONObject("expected");
            assertEquals(entry.getString("iso") + " course", expected.getBoolean("course"), dots.course);
            assertEquals(entry.getString("iso") + " todo", expected.getBoolean("todo"), dots.todo);
            assertEquals(entry.getString("iso") + " schedule", expected.getBoolean("schedule"), dots.schedule);
        }
    }

    @Test
    public void weekAheadMatchesCases() throws Exception {
        List<WeekDay> week = Items.weekAhead(dataset(), cases.getString("today"));
        JSONArray entries = cases.getJSONArray("weekAhead");
        assertEquals(7, week.size());
        assertEquals(entries.length(), week.size());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject expected = entries.getJSONObject(i);
            WeekDay actual = week.get(i);
            assertEquals(expected.getInt("offset"), i + 1);
            assertEquals("iso[" + i + "]", expected.getString("iso"), actual.iso);
            assertEquals("courseCount[" + i + "]", expected.getInt("courseCount"), actual.courseCount);
            assertEquals("firstCourseStart[" + i + "]",
                    integerOrNull(expected, "firstCourseStart"), actual.firstCourseStart);
            assertIds(expected.getJSONArray("itemIds"), actual.items);
        }
    }

    @Test
    public void archiveListMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("archiveList");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String filter = entry.getString("filter");
            List<ArchiveGroup> groups = Items.archiveList(dataset(), today, filter);
            JSONArray expectedGroups = entry.getJSONArray("groups");

            assertEquals(filter + " group count", expectedGroups.length(), groups.size());
            for (int g = 0; g < expectedGroups.length(); g++) {
                JSONObject expectedGroup = expectedGroups.getJSONObject(g);
                ArchiveGroup group = groups.get(g);
                assertEquals(filter + " group iso[" + g + "]", expectedGroup.getString("iso"), group.iso);

                JSONArray expectedEntries = expectedGroup.getJSONArray("entries");
                assertEquals(filter + " entry count[" + g + "]", expectedEntries.length(), group.entries.size());
                for (int e = 0; e < expectedEntries.length(); e++) {
                    JSONObject expectedEntry = expectedEntries.getJSONObject(e);
                    ArchiveEntry actualEntry = group.entries.get(e);
                    assertEquals(filter + " entry id[" + g + "][" + e + "]",
                            expectedEntry.getString("id"), actualEntry.item.optString("id"));
                    assertEquals(filter + " entry state[" + g + "][" + e + "]",
                            expectedEntry.getString("state"), actualEntry.state);
                    if (expectedEntry.isNull("at")) {
                        assertNull(filter + " entry at[" + g + "][" + e + "]", actualEntry.at);
                    } else {
                        assertEquals(filter + " entry at[" + g + "][" + e + "]",
                                expectedEntry.getString("at"), actualEntry.at);
                    }
                }
            }
        }
    }

    @Test
    public void reviewTriggerMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("reviewTriggerMinutes");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Object rawItems = entry.opt("items");
            List<JSONObject> input;
            if (rawItems instanceof String && "dataset".equals(rawItems)) {
                input = dataset();
            } else {
                input = toList(entry.getJSONArray("items"));
            }
            assertEquals(entry.getString("name"), entry.getInt("expected"),
                    Items.reviewTriggerMinutes(input, today));
        }
    }

    @Test
    public void reviewListMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("reviewList");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            int nowMinutes = entry.getInt("nowMinutes");
            List<JSONObject> result = Items.reviewList(dataset(), today, Integer.valueOf(nowMinutes));
            assertIds(entry.getJSONArray("expectedIds"), result);
        }
    }

    @Test
    public void nowNextMatchesCases() throws Exception {
        String today = cases.getString("today");
        JSONArray entries = cases.getJSONArray("nowNext");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            int nowMinutes = entry.getInt("nowMinutes");
            NowNext actual = Items.nowNext(dataset(), today, nowMinutes);

            JSONObject expectedCurrent = entry.isNull("expectedCurrent")
                    ? null
                    : entry.getJSONObject("expectedCurrent");
            JSONObject expectedNext = entry.isNull("expectedNext")
                    ? null
                    : entry.getJSONObject("expectedNext");
            assertSlot("current[" + i + "]", expectedCurrent, actual.current);
            assertSlot("next[" + i + "]", expectedNext, actual.next);
            assertEquals("freeUntil[" + i + "]", integerOrNull(entry, "freeUntil"), actual.freeUntil);
        }
    }

    @Test
    public void postponeMatchesCases() throws Exception {
        JSONObject postpone = cases.getJSONObject("postpone");
        JSONObject initial = postpone.getJSONObject("initial");
        JSONArray targets = postpone.getJSONArray("targets");
        JSONObject expected = postpone.getJSONObject("expected");

        JSONObject current = initial;
        for (int i = 0; i < targets.length(); i++) {
            current = Items.applyPostpone(current, targets.getString(i));
        }

        assertFalse(current.has("id"));
        assertFalse(current.has("_row_id"));
        assertEquals(expected.getString("date"), current.getString("date"));
        assertEquals(expected.getString("originalDate"), current.getString("originalDate"));
        assertEquals(expected.getInt("postponeCount"), current.getInt("postponeCount"));
        assertEquals("原始事项不应被修改", "2026-11-24", initial.getString("date"));
        assertTrue("原始事项保留行 id", initial.has("id"));
    }

    @Test
    public void termWeekMatchesCases() throws Exception {
        JSONObject meta = cases.getJSONObject("meta");
        JSONArray entries = cases.getJSONArray("termWeek");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String date = entry.getString("date");
            String expected = entry.getString("expected");
            assertEquals("termWeek string " + date, expected, Items.termWeekLabel(meta, date));
            assertEquals("termWeek LocalDate " + date, expected,
                    Items.termWeekLabel(meta, LocalDate.parse(date)));
        }
    }

    @Test
    public void metaHelpersMatchCases() throws Exception {
        JSONObject api = cases.getJSONObject("api");
        assertEquals(api.getString("metaId"), Items.metaId(api.getString("userId")));

        JSONObject meta = cases.getJSONObject("meta");
        JSONObject defaults = Items.defaultMeta();
        assertEquals("meta", defaults.getString("type"));
        assertEquals(Items.SUPPORTED_SCHEMA, defaults.getInt("schemaVersion"));
        assertEquals(meta.getString("termName"), defaults.getString("termName"));
        assertEquals(meta.getString("termStart"), defaults.getString("termStart"));
        assertEquals(meta.getInt("termWeeks"), defaults.getInt("termWeeks"));
    }

    @Test
    public void applyDoneFollowsCoreContract() throws Exception {
        JSONObject todo = findItem("todo-today");
        JSONObject done = Items.applyDone(todo, true, "2026-11-24T20:30:00+08:00");
        assertFalse(done.has("id"));
        assertFalse(done.has("_row_id"));
        assertTrue(done.getBoolean("done"));
        assertEquals("2026-11-24T20:30:00+08:00", done.getString("completedAt"));

        JSONObject undone = Items.applyDone(done, false, "2026-11-24T20:31:00+08:00");
        assertFalse(undone.getBoolean("done"));
        assertFalse(undone.has("completedAt"));
        assertTrue("原始事项不应被修改", todo.has("id"));
    }

    @Test
    public void applyDropAndRestoreFollowCoreContract() throws Exception {
        JSONObject pending = findItem("pending");
        JSONObject dropped = Items.applyDrop(pending, "2026-11-24T21:00:00+08:00");
        assertFalse(dropped.has("id"));
        assertFalse(dropped.has("_row_id"));
        assertEquals("dropped", dropped.getString("status"));
        assertEquals("2026-11-24T21:00:00+08:00", dropped.getString("droppedAt"));

        JSONObject restored = Items.applyRestore(dropped);
        assertFalse(restored.has("id"));
        assertFalse(restored.has("_row_id"));
        assertFalse(restored.has("status"));
        assertFalse(restored.has("droppedAt"));

        JSONObject restoredDone = Items.applyRestore(findItem("done-today"));
        assertFalse(restoredDone.getBoolean("done"));
        assertFalse(restoredDone.has("completedAt"));
    }

    // ------------------------------------------------------------------
    // Fixtures and assertions
    // ------------------------------------------------------------------

    private static JSONObject readCases() throws IOException {
        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path direct = dir.resolve("shared").resolve("rules-testcases.json");
            if (Files.isRegularFile(direct)) return readJson(direct);
            Path nested = dir.resolve("CalendarBoard").resolve("shared").resolve("rules-testcases.json");
            if (Files.isRegularFile(nested)) return readJson(nested);
        }
        throw new FileNotFoundException("未找到 shared/rules-testcases.json，起始目录：" + current);
    }

    private static JSONObject readJson(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        try {
            return new JSONObject(text);
        } catch (org.json.JSONException error) {
            throw new IOException("无法解析 " + path, error);
        }
    }

    private static List<JSONObject> dataset() throws Exception {
        return toList(cases.getJSONArray("dataset"));
    }

    private static List<JSONObject> toList(JSONArray array) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
        return result;
    }

    private static List<String> stringList(JSONArray array) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    private static Map<String, JSONObject> byId(List<JSONObject> items) {
        Map<String, JSONObject> result = new java.util.LinkedHashMap<>();
        for (JSONObject item : items) {
            String id = item.optString("id", "");
            if (!id.isEmpty()) result.put(id, item);
        }
        return result;
    }

    private static JSONObject item(Map<String, JSONObject> byId, String id) {
        JSONObject item = byId.get(id);
        assertNotNull("缺少测试事项：" + id, item);
        return item;
    }

    private static JSONObject findItem(String id) throws Exception {
        return item(byId(dataset()), id);
    }

    private static Integer integerOrNull(JSONObject object, String key) throws Exception {
        if (!object.has(key) || object.isNull(key)) return null;
        return Integer.valueOf(object.getInt(key));
    }

    private static void assertIds(JSONArray expected, List<JSONObject> actual) throws Exception {
        assertEquals(expected.length(), actual.size());
        for (int i = 0; i < expected.length(); i++) {
            assertEquals("id[" + i + "]", expected.getString(i), actual.get(i).optString("id"));
        }
    }

    private static void assertSlot(String label, JSONObject expected, CourseSlot actual) {
        if (expected == null) {
            assertNull(label, actual);
            return;
        }
        assertNotNull(label, actual);
        assertEquals(label + " id", expected.optString("id"), actual.id);
        assertEquals(label + " title", expected.optString("title"), actual.title);
        assertEquals(label + " place", expected.optString("place"), actual.place);
        assertEquals(label + " start", Integer.valueOf(expected.optInt("start")), actual.start);
        if (expected.isNull("end")) {
            assertNull(label + " end", actual.end);
        } else {
            assertEquals(label + " end", Integer.valueOf(expected.optInt("end")), actual.end);
        }
    }
}
