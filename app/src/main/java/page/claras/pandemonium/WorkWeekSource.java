package page.claras.pandemonium;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates the planned Work week; completed shifts remain visible until Monday rollover. */
final class WorkWeekSource {
    static final String METHOD = "work.schedule.currentWeek";
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    static LocalDate weekStart(Instant now) {
        return now.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static JSONObject read(JSONObject response, Instant now) throws Exception {
        LocalDate start = weekStart(now);
        if (!ZONE.getId().equals(response.getString("timezone"))
                || !start.toString().equals(response.getString("weekStart"))
                || !start.plusDays(7).toString().equals(response.getString("weekEndExclusive"))) {
            throw new IllegalArgumentException("Work week does not match the current Berlin week");
        }
        String source = response.getString("source");
        if (!source.equals("calendar") && !source.equals("approved-plan"))
            throw new IllegalArgumentException("Unknown Work schedule source");
        Instant generated = Instant.parse(response.getString("generatedAt"));
        JSONArray days = response.getJSONArray("days");
        if (days.length() != 7) throw new IllegalArgumentException("Incomplete Work week");
        List<JSONObject> shifts = new ArrayList<>();
        Map<String, String> identities = new HashMap<>();
        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            JSONObject day = days.getJSONObject(dayIndex);
            String date = start.plusDays(dayIndex).toString();
            if (!date.equals(day.getString("day"))) throw new IllegalArgumentException("Unexpected Work day");
            JSONArray items = day.getJSONArray("shifts");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.getString("eventId"), title = item.getString("title");
                Instant begins = Instant.parse(item.getString("startTime"));
                Instant ends = Instant.parse(item.getString("endTime"));
                ZoneId.of(item.getString("timezone"));
                if (id.isEmpty() || title.isEmpty() || !ends.isAfter(begins)
                        || !begins.atZone(ZONE).toLocalDate().toString().equals(date)) {
                    throw new IllegalArgumentException("Invalid planned Work shift");
                }
                String identity = begins + "\n" + ends + "\n" + title;
                String previous = identities.putIfAbsent(id, identity);
                if (previous != null) {
                    if (!previous.equals(identity)) throw new IllegalArgumentException("Conflicting Work shift identity");
                    continue;
                }
                shifts.add(new JSONObject().put("eventId", id).put("title", title)
                    .put("startTime", begins.toString()).put("endTime", ends.toString())
                    .put("timezone", ZONE.getId()).put("workDay", date));
            }
        }
        shifts.sort(Comparator.comparing((JSONObject item) -> Instant.parse(item.optString("startTime")))
            .thenComparing(item -> item.optString("eventId")));
        return new JSONObject().put("workWeek", true).put("timezone", ZONE.getId())
            .put("weekStart", start.toString()).put("weekEndExclusive", start.plusDays(7).toString())
            .put("source", source).put("generatedAt", generated.toString()).put("updatedAt", generated.toEpochMilli())
            .put("items", new JSONArray(shifts));
    }

    static boolean isCurrent(JSONObject section, Instant now) {
        if (section == null || !section.optBoolean("workWeek") || !ZONE.getId().equals(section.optString("timezone"))) return false;
        LocalDate start = weekStart(now);
        return start.toString().equals(section.optString("weekStart"))
            && start.plusDays(7).toString().equals(section.optString("weekEndExclusive"));
    }

    static JSONArray items(JSONObject section, Instant now) {
        JSONArray items = section == null ? null : section.optJSONArray("items");
        return isCurrent(section, now) && items != null ? items : new JSONArray();
    }
}
