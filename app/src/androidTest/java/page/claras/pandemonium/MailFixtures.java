package page.claras.pandemonium;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

final class MailFixtures {
    static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static String cursor(int value) {
        byte[] bytes = new byte[32];
        java.nio.ByteBuffer.wrap(bytes).putInt(value);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    static JSONObject message(String id, String source, Instant date, Boolean unread) throws Exception {
        JSONObject value = new JSONObject().put("source", "gmail").put("sourceId", source).put("messageId", id)
            .put("messageRef", "email:v1:" + source + ":" + Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))
            .put("subject", id).put("date", date.toString()).put("snippet", "Synthetic summary")
            .put("from", new JSONArray().put(new JSONObject().put("name", "Fixture sender").put("address", "sender@example.test")))
            .put("handoff", new JSONObject().put("available", false));
        if (unread != null) value.put("unread", unread);
        return value;
    }
    static JSONArray messages(int count, Instant now) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < count; i++) messages.put(message("Mail " + (i + 1), i % 2 == 0 ? A : B,
            now.minusSeconds(3600L + i), i == 2 ? null : i % 2 == 0));
        return messages;
    }
    static JSONObject coverage(JSONArray messages, Instant now) throws Exception {
        int a = 0, b = 0;
        for (int i = 0; i < messages.length(); i++) if (A.equals(messages.getJSONObject(i).getString("sourceId"))) a++; else b++;
        return new JSONObject().put("complete", true).put("from", now.minusMillis(MailWindowSource.WINDOW_MS).toString()).put("to", now.toString())
            .put("statusAvailable", true).put("connections", new JSONArray()
                .put(connection(A, a, "available")).put(connection(B, b, "available")))
            .put("syncConnections", new JSONArray()
                .put(new JSONObject().put("sourceId", A).put("source", "gmail").put("state", "available").put("lastSuccessAt", now.minusSeconds(600).toString()))
                .put(new JSONObject().put("sourceId", B).put("source", "gmail").put("state", "available").put("lastSuccessAt", now.minusSeconds(1200).toString())))
            .put("lastSuccessfulSyncAt", now.minusSeconds(600).toString());
    }
    static JSONObject connection(String id, int count, String state) throws Exception {
        return new JSONObject().put("sourceId", id).put("source", "gmail").put("state", state).put("returnedMessageCount", count);
    }
    static final class Pager implements MailWindowSource.Api {
        final JSONArray messages;
        final JSONObject coverage;
        final List<JSONObject> requests = new ArrayList<>();
        private int offset;
        Pager(JSONArray messages, Instant now) throws Exception { this.messages = messages; coverage = coverage(messages, now); }
        @Override public JSONObject call(JSONObject params) throws Exception {
            requests.add(new JSONObject(params.toString()));
            assertEquals(100, params.getInt("limit"));
            assertEquals(offset == 0 ? 1 : 2, params.length());
            if (offset == 0) assertFalse(params.has("cursor")); else assertEquals(cursor(offset), params.getString("cursor"));
            JSONArray page = new JSONArray();
            int end = Math.min(offset + 100, messages.length());
            while (offset < end) page.put(messages.getJSONObject(offset++));
            JSONObject reply = new JSONObject().put("messages", page).put("coverage", new JSONObject(coverage.toString()));
            if (offset < messages.length()) reply.put("nextCursor", cursor(offset));
            return reply;
        }
    }
    static JSONObject save(MailWidgetStore store, JSONArray messages, Instant now) throws Exception {
        try (MailWidgetStore.Writer writer = store.writer()) {
            JSONObject header = MailWindowSource.read(new Pager(messages, now), writer, now);
            writer.commit(header);
            return header;
        }
    }
}
