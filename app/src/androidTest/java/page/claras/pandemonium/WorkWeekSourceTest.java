package page.claras.pandemonium;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkWeekSourceTest {
    private static final Instant SUNDAY = Instant.parse("2026-10-25T12:00:00Z");

    @Test public void retainsMondayAndBothSidesOfTheSundayClockChange() throws Exception {
        JSONObject response = week(SUNDAY);
        add(response, 0, shift("monday", "2026-10-19T08:00:00+02:00", "2026-10-19T16:00:00+02:00"));
        add(response, 6, shift("sunday", "2026-10-25T02:15:00+02:00", "2026-10-25T02:45:00+01:00"));
        JSONObject result = WorkWeekSource.read(response, SUNDAY);
        assertEquals("2026-10-19", result.getString("weekStart"));
        assertEquals("2026-10-26", result.getString("weekEndExclusive"));
        assertEquals(2, WorkWeekSource.items(result, SUNDAY).length());
        assertEquals("monday", result.getJSONArray("items").getJSONObject(0).getString("eventId"));
        JSONObject sunday = result.getJSONArray("items").getJSONObject(1);
        assertEquals(90 * 60, Instant.parse(sunday.getString("endTime")).getEpochSecond()
            - Instant.parse(sunday.getString("startTime")).getEpochSecond());
        assertEquals(169 * 3600, LocalDate.parse("2026-10-26").atStartOfDay(WorkWeekSource.ZONE).toEpochSecond()
            - LocalDate.parse("2026-10-19").atStartOfDay(WorkWeekSource.ZONE).toEpochSecond());
    }

    @Test public void doesNotSilentlyCapThePlannedShiftList() throws Exception {
        JSONObject response = week(SUNDAY);
        for (int i = 0; i < 150; i++) {
            Instant starts = Instant.parse("2026-10-19T06:00:00Z").plusSeconds(i * 60);
            add(response, 0, shift("shift-" + i, starts.toString(), starts.plusSeconds(60).toString()));
        }
        assertEquals(150, WorkWeekSource.read(response, SUNDAY).getJSONArray("items").length());
    }

    @Test public void rejectsIncompleteWeeksAndConflictingIdentities() throws Exception {
        JSONObject incomplete = week(SUNDAY);
        incomplete.getJSONArray("days").remove(6);
        assertThrows(IllegalArgumentException.class, () -> WorkWeekSource.read(incomplete, SUNDAY));
        JSONObject conflict = week(SUNDAY);
        add(conflict, 0, shift("same", "2026-10-19T06:00:00Z", "2026-10-19T14:00:00Z"));
        add(conflict, 0, shift("same", "2026-10-19T07:00:00Z", "2026-10-19T15:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> WorkWeekSource.read(conflict, SUNDAY));
        JSONObject empty = WorkWeekSource.read(week(SUNDAY).put("source", "approved-plan"), SUNDAY);
        assertEquals(0, WorkWeekSource.items(empty, SUNDAY).length());
        assertEquals("approved-plan", empty.getString("source"));
    }

    @Test public void keepsSourceFreshnessAndExpiresAtBerlinMondayRegardlessOfDeviceZone() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            JSONObject response = week(SUNDAY).put("generatedAt", "2026-10-25T00:00:00Z");
            add(response, 0, shift("monday", "2026-10-19T06:00:00Z", "2026-10-19T14:00:00Z"));
            JSONObject result = WorkWeekSource.read(response, SUNDAY);
            assertEquals(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), result.getLong("updatedAt"));
            assertEquals(1, WorkWeekSource.items(result, Instant.parse("2026-10-25T22:59:59Z")).length());
            assertEquals(0, WorkWeekSource.items(result, Instant.parse("2026-10-25T23:00:00Z")).length());
            assertEquals(0, WorkWeekSource.items(new JSONObject().put("items", new JSONArray()), SUNDAY).length());
        } finally { TimeZone.setDefault(previous); }
    }

    static JSONObject week(Instant now) throws Exception {
        LocalDate monday = WorkWeekSource.weekStart(now);
        JSONArray days = new JSONArray();
        for (int i = 0; i < 7; i++) days.put(new JSONObject().put("day", monday.plusDays(i).toString()).put("shifts", new JSONArray()));
        return new JSONObject().put("generatedAt", now.toString()).put("timezone", "Europe/Berlin")
            .put("weekStart", monday.toString()).put("weekEndExclusive", monday.plusDays(7).toString())
            .put("source", "calendar").put("days", days);
    }

    static JSONObject shift(String id, String starts, String ends) throws Exception {
        return new JSONObject().put("eventId", id).put("title", "Late Shift")
            .put("startTime", starts).put("endTime", ends).put("timezone", "Europe/Berlin");
    }

    static void add(JSONObject response, int day, JSONObject shift) throws Exception {
        response.getJSONArray("days").getJSONObject(day).getJSONArray("shifts").put(shift);
    }
}
