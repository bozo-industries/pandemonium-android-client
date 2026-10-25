package page.claras.pandemonium;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class MailWindowSourceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final class Sink implements MailWindowSource.Sink {
        final JSONArray messages = new JSONArray();
        @Override public void append(JSONObject message) { messages.put(message); }
        @Override public int count() { return messages.length(); }
    }

    @Test public void exhaustsOpaquePagesAndRetainsReadUnreadAndUnknownStatesAcrossSources() throws Exception {
        MailFixtures.Pager api = new MailFixtures.Pager(MailFixtures.messages(305, NOW), NOW);
        api.messages.getJSONObject(2).getJSONArray("from").getJSONObject(0).put("address", "");
        api.messages.getJSONObject(3).put("from", new JSONArray());
        Sink sink = new Sink();
        JSONObject metadata = MailWindowSource.read(api, sink, NOW);
        assertEquals(4, api.requests.size()); assertEquals(305, metadata.getInt("count"));
        assertTrue(metadata.getBoolean("paginationComplete"));
        assertTrue(sink.messages.getJSONObject(0).getBoolean("unread"));
        assertFalse(sink.messages.getJSONObject(1).getBoolean("unread"));
        assertFalse(sink.messages.getJSONObject(2).has("unread"));
        assertEquals("", sink.messages.getJSONObject(2).getJSONArray("from").getJSONObject(0).getString("address"));
        assertEquals(0, sink.messages.getJSONObject(3).getJSONArray("from").length());
        assertEquals("Mail 305", sink.messages.getJSONObject(304).getString("subject"));
        assertEquals(MailFixtures.A, sink.messages.getJSONObject(0).getString("sourceId"));
        assertEquals(MailFixtures.B, sink.messages.getJSONObject(1).getString("sourceId"));
        assertFalse(api.requests.toString().contains("refresh"));
        assertFalse(api.requests.toString().contains("index"));
    }

    @Test public void rejectsChangedCoverageRepeatedCursorsAndEmptyContinuationPages() throws Exception {
        JSONArray messages = MailFixtures.messages(201, NOW);
        for (String corruption : new String[]{"coverage", "cycle", "empty", "null_cursor"}) {
            MailFixtures.Pager api = new MailFixtures.Pager(messages, NOW);
            AtomicInteger calls = new AtomicInteger();
            try {
                MailWindowSource.read(params -> {
                    JSONObject reply = api.call(params);
                    int call = calls.incrementAndGet();
                    if (call == 2 && corruption.equals("coverage")) reply.getJSONObject("coverage").put("statusAvailable", false);
                    if (call == 2 && corruption.equals("cycle")) reply.put("nextCursor", MailFixtures.cursor(100));
                    if (call == 1 && corruption.equals("empty")) reply.put("messages", new JSONArray());
                    if (call == 1 && corruption.equals("null_cursor")) reply.put("nextCursor", JSONObject.NULL);
                    return reply;
                }, new Sink(), NOW);
                fail("Malformed traversal completed: " + corruption);
            } catch (MailWindowSource.InvalidSnapshot expected) { }
        }
    }

    @Test public void keepsTheSevenElapsedDayBoundaryAndRejectsUndatedFutureAndMisqualifiedRows() throws Exception {
        JSONObject lower = MailFixtures.message("lower", MailFixtures.A, NOW.minusMillis(MailWindowSource.WINDOW_MS), false);
        assertEquals(1, MailWindowSource.read(new MailFixtures.Pager(new JSONArray().put(lower), NOW), new Sink(), NOW).getInt("count"));
        for (String corruption : new String[]{"undated", "future", "old", "source", "ref", "read"}) {
            JSONObject row = MailFixtures.message("identity", MailFixtures.A, NOW.minusSeconds(1), false);
            if (corruption.equals("undated")) row.remove("date");
            if (corruption.equals("future")) row.put("date", NOW.toString());
            if (corruption.equals("old")) row.put("date", NOW.minusMillis(MailWindowSource.WINDOW_MS + 1).toString());
            if (corruption.equals("source")) row.put("sourceId", "cccccccccccccccccccccccccccccccc");
            if (corruption.equals("ref")) row.put("messageRef", "email:v1:" + MailFixtures.B + ":eA");
            if (corruption.equals("read")) row.put("unread", "false");
            MailFixtures.Pager api = new MailFixtures.Pager(new JSONArray().put(row), NOW);
            if (corruption.equals("source")) api.coverage.getJSONArray("connections").getJSONObject(1).put("sourceId", MailFixtures.B);
            try { MailWindowSource.read(api, new Sink(), NOW); fail("Invalid row completed: " + corruption); }
            catch (MailWindowSource.InvalidSnapshot expected) { }
        }
    }

    @Test public void preservesPartialSourceCoverageAndDoesNotInventFreshnessOrReaderUris() throws Exception {
        JSONArray messages = new JSONArray().put(MailFixtures.message("one", MailFixtures.A, NOW.minusSeconds(1), true)
            .put("handoff", new JSONObject().put("available", true).put("uri", "javascript:unsafe"))
            .put("viewUri", "content://unverified/mail"));
        MailFixtures.Pager api = new MailFixtures.Pager(messages, NOW);
        api.coverage.put("complete", false).put("statusAvailable", false);
        api.coverage.getJSONArray("connections").getJSONObject(1).put("state", "failed");
        Sink sink = new Sink();
        JSONObject metadata = MailWindowSource.read(api, sink, NOW);
        assertFalse(metadata.getJSONObject("coverage").getBoolean("complete"));
        assertNull(MailWindowSource.oldestSync(metadata));
        assertFalse(sink.messages.getJSONObject(0).getBoolean("handoffAvailable"));
        assertFalse(sink.messages.getJSONObject(0).has("viewUri"));
        assertFalse(sink.messages.getJSONObject(0).toString().contains("unsafe"));
        MailFixtures.Pager complete = new MailFixtures.Pager(messages, NOW);
        JSONObject full = MailWindowSource.read(complete, new Sink(), NOW);
        assertEquals(NOW.minusSeconds(1200), MailWindowSource.oldestSync(full));
        full.getJSONObject("coverage").getJSONArray("syncConnections").getJSONObject(1).remove("lastSuccessAt");
        assertNull(MailWindowSource.oldestSync(full));
    }

    @Test public void refusesTraversalAtTheSafetyBoundAndAtSnapshotExpiry() throws Exception {
        JSONArray repeated = new JSONArray();
        for (int i = 0; i < 100; i++) repeated.put(MailFixtures.message("same", MailFixtures.A, NOW.minusSeconds(1), false));
        JSONObject coverage = MailFixtures.coverage(repeated, NOW);
        coverage.getJSONArray("connections").getJSONObject(0).put("returnedMessageCount", 50_000);
        AtomicInteger pages = new AtomicInteger();
        MailWindowSource.Sink counter = new MailWindowSource.Sink() {
            int count;
            @Override public void append(JSONObject message) { count++; }
            @Override public int count() { return count; }
        };
        try {
            MailWindowSource.read(params -> new JSONObject().put("coverage", coverage).put("messages", repeated)
                .put("nextCursor", MailFixtures.cursor(pages.incrementAndGet())), counter, NOW);
            fail("Safety-bound continuation completed");
        } catch (MailWindowSource.LimitExceeded expected) { assertEquals(500, pages.get()); }
        AtomicLong ticks = new AtomicLong(1000);
        try {
            MailWindowSource.read(params -> { ticks.addAndGet(MailWindowSource.SNAPSHOT_TTL_MS); return new MailFixtures.Pager(new JSONArray(), NOW).call(params); },
                new Sink(), NOW, ticks::get);
            fail("Expired traversal completed");
        } catch (MailWindowSource.CursorExpired expected) { }
        assertEquals("expired", HomeWidgetSync.mailFailure(new MailWindowSource.CursorExpired()));
        assertEquals("limit", HomeWidgetSync.mailFailure(new MailWindowSource.LimitExceeded()));
        assertEquals("incomplete", HomeWidgetSync.mailFailure(new MailWindowSource.InvalidSnapshot()));
    }

    @Test public void reportsCoverageFreshnessAndSavedUpdateFailuresWithoutClaimingCompleteness() throws Exception {
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject metadata = new JSONObject().put("coverage", MailFixtures.coverage(new JSONArray(), NOW));
        assertTrue(HomeWidgetViews.mailStatus(context, metadata).contains("Sources 2/2"));
        assertTrue(HomeWidgetViews.mailStatus(context, metadata).contains("Oldest sync"));
        metadata.getJSONObject("coverage").put("complete", false).put("statusAvailable", false)
            .getJSONArray("connections").getJSONObject(1).put("state", "failed");
        String partial = HomeWidgetViews.mailStatus(context, metadata);
        assertTrue(partial.contains("Sources 1/2")); assertTrue(partial.contains("Some sources unavailable"));
        assertTrue(partial.contains("Sync time unavailable"));
        metadata.put("refreshError", "expired");
        assertTrue(HomeWidgetViews.mailStatus(context, metadata).contains("Mail update expired"));
        metadata.put("refreshError", "limit");
        assertTrue(HomeWidgetViews.mailStatus(context, metadata).contains("Mail incomplete: update limit reached"));
    }
}
