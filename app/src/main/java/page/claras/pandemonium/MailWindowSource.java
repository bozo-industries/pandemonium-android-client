package page.claras.pandemonium;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Consumes one immutable server snapshot; a cursor failure never becomes a complete shorter list. */
final class MailWindowSource {
    static final String METHOD = "email.widget.mail";
    static final int PAGE_SIZE = 100, MAX_MESSAGES = 50_000;
    static final long WINDOW_MS = 7 * 86_400_000L;
    static final long SNAPSHOT_TTL_MS = 15 * 60_000L;
    interface Api { JSONObject call(JSONObject params) throws Exception; }
    interface Sink {
        void append(JSONObject message) throws Exception;
        int count();
    }
    static final class InvalidSnapshot extends IOException {
        InvalidSnapshot() { super("Mail snapshot is incomplete"); }
    }
    static final class LimitExceeded extends IOException {
        LimitExceeded() { super("Mail snapshot exceeds its safety bound"); }
    }
    static final class CursorExpired extends IOException {
        CursorExpired() { super("Mail snapshot expired"); }
    }

    static JSONObject read(Api api, Sink sink, Instant fetchedAt) throws Exception {
        return read(api, sink, fetchedAt, android.os.SystemClock::elapsedRealtime);
    }

    static JSONObject read(Api api, Sink sink, Instant fetchedAt, java.util.function.LongSupplier ticks) throws Exception {
        JSONObject coverage = null;
        String fingerprint = null, cursor = null;
        Instant from = null, to = null, previousDate = null;
        Set<String> cursors = new HashSet<>();
        long deadline = ticks.getAsLong() + SNAPSHOT_TTL_MS;
        int returned = 0;
        do {
            if (ticks.getAsLong() >= deadline) throw new CursorExpired();
            JSONObject params = new JSONObject().put("limit", PAGE_SIZE);
            if (cursor != null) params.put("cursor", cursor);
            JSONObject reply = api.call(params);
            if (ticks.getAsLong() >= deadline) throw new CursorExpired();
            JSONObject pageCoverage = reply == null ? null : reply.optJSONObject("coverage");
            validateCoverage(pageCoverage);
            String nextFingerprint = canonical(pageCoverage);
            if (coverage == null) {
                coverage = new JSONObject(pageCoverage.toString());
                fingerprint = nextFingerprint;
                from = timestamp(coverage, "from");
                to = timestamp(coverage, "to");
                if (!java.time.Duration.between(from, to).equals(java.time.Duration.ofDays(7))) throw new InvalidSnapshot();
            } else if (!fingerprint.equals(nextFingerprint)) throw new InvalidSnapshot();
            JSONArray messages = reply.optJSONArray("messages");
            if (messages == null || messages.length() > PAGE_SIZE) throw new InvalidSnapshot();
            for (int i = 0; i < messages.length(); i++) {
                if (++returned > MAX_MESSAGES) throw new LimitExceeded();
                JSONObject message = message(messages.optJSONObject(i), coverage, from, to);
                Instant date = Instant.parse(message.getString("date"));
                if (previousDate != null && date.isAfter(previousDate)) throw new InvalidSnapshot();
                previousDate = date;
                sink.append(message);
            }
            cursor = null;
            if (reply.has("nextCursor")) {
                Object value = reply.opt("nextCursor");
                if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{43}")
                        || messages.length() == 0 || !cursors.add((String) value)) throw new InvalidSnapshot();
                if (returned >= MAX_MESSAGES) throw new LimitExceeded();
                cursor = (String) value;
            }
        } while (cursor != null);
        return new JSONObject().put("schema", 1).put("coverage", coverage)
            .put("updatedAt", fetchedAt.toEpochMilli()).put("count", sink.count())
            .put("paginationComplete", true);
    }

    private static JSONObject message(JSONObject raw, JSONObject coverage, Instant from, Instant to) throws Exception {
        if (raw == null) throw new InvalidSnapshot();
        String sourceId = string(raw, "sourceId", 32, false);
        if (!sourceId.matches("[a-f0-9]{32}")) throw new InvalidSnapshot();
        String source = string(raw, "source", 16, false);
        boolean admitted = false;
        JSONArray connections = coverage.getJSONArray("connections");
        for (int i = 0; i < connections.length(); i++) {
            JSONObject connection = connections.getJSONObject(i);
            if (sourceId.equals(connection.getString("sourceId"))) {
                admitted = source.equals(connection.getString("source")) && "available".equals(connection.getString("state"));
                break;
            }
        }
        if (!admitted) throw new InvalidSnapshot();
        String id = string(raw, "messageId", 2048, false);
        String ref = string(raw, "messageRef", 11_000, false);
        String expected = "email:v1:" + sourceId + ":" + Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!expected.equals(ref)) throw new InvalidSnapshot();
        Instant date = timestamp(raw, "date");
        if (date.isBefore(from) || !date.isBefore(to)) throw new InvalidSnapshot();
        JSONObject value = new JSONObject().put("source", source).put("sourceId", sourceId)
            .put("messageId", id).put("messageRef", ref).put("date", date.toString())
            .put("subject", string(raw, "subject", 500, true)).put("snippet", string(raw, "snippet", 1000, true))
            .put("handoffAvailable", false);
        JSONArray senders = raw.optJSONArray("from");
        if (raw.has("from") && senders == null) throw new InvalidSnapshot();
        JSONArray safeSenders = new JSONArray();
        if (senders != null) {
            if (senders.length() > 1) throw new InvalidSnapshot();
            if (senders.length() == 1) {
                JSONObject sender = senders.optJSONObject(0);
                if (sender == null) throw new InvalidSnapshot();
                safeSenders.put(new JSONObject().put("name", string(sender, "name", 200, true))
                    .put("address", string(sender, "address", 320, true)));
            }
        }
        value.put("from", safeSenders);
        if (raw.has("unread")) value.put("unread", bool(raw, "unread"));
        // Server summaries carry no verified app-specific URI; never promote an arbitrary link into a handoff.
        return value;
    }

    private static void validateCoverage(JSONObject coverage) throws Exception {
        if (coverage == null) throw new InvalidSnapshot();
        boolean complete = bool(coverage, "complete");
        bool(coverage, "statusAvailable");
        timestamp(coverage, "from"); timestamp(coverage, "to");
        JSONArray connections = coverage.optJSONArray("connections");
        if (connections == null) throw new InvalidSnapshot();
        Set<String> ids = new HashSet<>();
        boolean allAvailable = true;
        for (int i = 0; i < connections.length(); i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection == null) throw new InvalidSnapshot();
            String id = string(connection, "sourceId", 32, false);
            String source = string(connection, "source", 16, false);
            String state = string(connection, "state", 16, false);
            Object count = connection.opt("returnedMessageCount");
            if (!id.matches("[a-f0-9]{32}") || !ids.add(id)
                    || !(source.equals("gmail") || source.equals("imap") || source.equals("mock"))
                    || !(state.equals("available") || state.equals("failed"))
                    || !(count instanceof Number) || ((Number) count).doubleValue() != ((Number) count).longValue()
                    || ((Number) count).longValue() < 0 || ((Number) count).longValue() > MAX_MESSAGES) throw new InvalidSnapshot();
            allAvailable &= state.equals("available");
        }
        if (complete != allAvailable) throw new InvalidSnapshot();
        if (coverage.has("lastSuccessfulSyncAt")) timestamp(coverage, "lastSuccessfulSyncAt");
        if (coverage.has("syncConnections")) {
            JSONArray sync = coverage.optJSONArray("syncConnections");
            if (sync == null) throw new InvalidSnapshot();
            Set<String> syncIds = new HashSet<>();
            for (int i = 0; i < sync.length(); i++) {
                JSONObject item = sync.optJSONObject(i);
                if (item == null) throw new InvalidSnapshot();
                String id = string(item, "sourceId", 32, false);
                String state = string(item, "state", 16, false);
                if (!id.matches("[a-f0-9]{32}") || !syncIds.add(id)
                        || !(state.equals("available") || state.equals("failed") || state.equals("unknown"))) throw new InvalidSnapshot();
                for (String field : new String[]{"lastSuccessAt", "lastAttemptAt", "lastCompletedAt"}) if (item.has(field)) timestamp(item, field);
            }
        }
    }

    static Instant oldestSync(JSONObject metadata) {
        try {
            JSONObject coverage = metadata.getJSONObject("coverage");
            if (!coverage.getBoolean("statusAvailable")) return null;
            JSONArray sources = coverage.getJSONArray("connections"), sync = coverage.optJSONArray("syncConnections");
            if (sync == null || sources.length() == 0) return null;
            Instant oldest = null;
            for (int i = 0; i < sources.length(); i++) {
                String id = sources.getJSONObject(i).getString("sourceId");
                Instant found = null;
                for (int j = 0; j < sync.length(); j++) {
                    JSONObject item = sync.getJSONObject(j);
                    if (id.equals(item.optString("sourceId")) && "available".equals(item.optString("state")) && item.has("lastSuccessAt"))
                        found = timestamp(item, "lastSuccessAt");
                }
                if (found == null) return null;
                if (oldest == null || found.isBefore(oldest)) oldest = found;
            }
            return oldest;
        } catch (Exception ignored) { return null; }
    }

    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeSet<String> keys = new TreeSet<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static String string(JSONObject value, String field, int limit, boolean optional) throws InvalidSnapshot {
        Object raw = value.opt(field);
        if (raw == null && optional) return "";
        if (!(raw instanceof String) || ((String) raw).length() > limit || (!optional && ((String) raw).isEmpty())) throw new InvalidSnapshot();
        return (String) raw;
    }
    private static boolean bool(JSONObject value, String field) throws InvalidSnapshot {
        Object raw = value.opt(field);
        if (!(raw instanceof Boolean)) throw new InvalidSnapshot();
        return (Boolean) raw;
    }
    private static Instant timestamp(JSONObject value, String field) throws InvalidSnapshot {
        try { return Instant.parse(string(value, field, 40, false)); }
        catch (Exception ignored) { throw new InvalidSnapshot(); }
    }
}
