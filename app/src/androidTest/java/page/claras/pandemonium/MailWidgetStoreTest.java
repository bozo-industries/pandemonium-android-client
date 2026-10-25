package page.claras.pandemonium;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import static org.junit.Assert.*;

public class MailWidgetStoreTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final MailWidgetStore store = new MailWidgetStore(context);
    private final Instant now = Instant.now();
    @Before public void before() throws Exception { store.clear(); }
    @After public void after() throws Exception { store.clear(); context.getSharedPreferences("mail-settings-fixture", 0).edit().clear().commit(); }

    @Test public void storesMoreThanTheOldCombinedCacheLimitAndReadsTheLastEncryptedPage() throws Exception {
        JSONArray messages = MailFixtures.messages(801, now);
        String snippet = new String(new char[1000]).replace('\0', 'x');
        for (int i = 0; i < messages.length(); i++) messages.getJSONObject(i).put("snippet", snippet);
        MailFixtures.save(store, messages, now);
        JSONObject metadata = store.metadata();
        assertEquals(801, metadata.getInt("count"));
        assertTrue(metadata.getJSONArray("pageStarts").length() > 1);
        try (MailWidgetStore.Reader reader = new MailWidgetStore(context).reader(now)) {
            assertEquals(801, reader.count());
            assertEquals("Mail 801", reader.row(800).getString("subject"));
            assertFalse(reader.row(799).getBoolean("unread"));
        }
        File root = new File(context.getFilesDir(), "mail-widget");
        long stored = 0;
        for (File entry : root.listFiles()) {
            if (entry.isDirectory()) for (File page : entry.listFiles()) {
                byte[] bytes = Files.readAllBytes(page.toPath()); stored += bytes.length;
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("Mail 801"));
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(snippet));
            }
        }
        assertTrue("Mail must not inherit the old 512k combined-cache limit", stored > 512_000);
    }

    @Test public void deduplicatesOnlyExactQualifiedIdentityAndRefusesConflictingSnapshotRows() throws Exception {
        JSONObject a = MailFixtures.message("same-provider-id", MailFixtures.A, now.minusSeconds(1), false);
        JSONObject b = MailFixtures.message("same-provider-id", MailFixtures.B, now.minusSeconds(1), false);
        MailFixtures.save(store, new JSONArray().put(a).put(b).put(new JSONObject(a.toString())), now);
        try (MailWidgetStore.Reader reader = store.reader(now)) {
            assertEquals(2, reader.count()); assertNotEquals(reader.row(0).getString("messageRef"), reader.row(1).getString("messageRef"));
        }
        String token = store.metadata().getString("snapshot");
        JSONObject conflict = new JSONObject(a.toString()).put("subject", "Different summary");
        try { MailFixtures.save(store, new JSONArray().put(a).put(conflict), now); fail("Conflicting same identity was silently selected"); }
        catch (MailWindowSource.InvalidSnapshot expected) { }
        assertEquals(token, store.metadata().getString("snapshot"));
    }

    @Test public void failedOrExpiredContinuationKeepsThePreviousSnapshotAndAnExplicitFailure() throws Exception {
        MailFixtures.save(store, MailFixtures.messages(3, now), now);
        String token = store.metadata().getString("snapshot");
        MailFixtures.Pager api = new MailFixtures.Pager(MailFixtures.messages(201, now), now);
        try (MailWidgetStore.Writer writer = store.writer()) {
            try {
                MailWindowSource.read(params -> { if (params.has("cursor")) throw new MailWindowSource.CursorExpired(); return api.call(params); }, writer, now);
                fail("Expired continuation completed");
            } catch (MailWindowSource.CursorExpired expected) { store.markFailure(HomeWidgetSync.mailFailure(expected)); }
        }
        assertEquals(token, store.metadata().getString("snapshot"));
        assertEquals("expired", store.metadata().getString("refreshError"));
        try (MailWidgetStore.Reader reader = store.reader(now)) { assertEquals(3, reader.count()); }
        MailFixtures.save(store, MailFixtures.messages(4, now), now);
        assertFalse(store.metadata().has("refreshError"));
    }

    @Test public void filtersAgedRowsRetainsActiveReadersAndInvalidatesStagingOnLogout() throws Exception {
        JSONArray rows = new JSONArray().put(MailFixtures.message("recent", MailFixtures.A, now.minusSeconds(1), false))
            .put(MailFixtures.message("lower", MailFixtures.B, now.minusMillis(MailWindowSource.WINDOW_MS), false));
        MailFixtures.save(store, rows, now);
        try (MailWidgetStore.Reader old = store.reader(now)) {
            assertEquals(2, old.count());
            try (MailWidgetStore.Reader later = store.reader(now.plusSeconds(1))) { assertEquals(1, later.count()); }
            MailFixtures.save(store, MailFixtures.messages(5, now), now);
            assertEquals("recent", old.row(0).getString("subject"));
            try (MailWidgetStore.Writer invalidated = store.writer()) {
                JSONObject pending = MailWindowSource.read(new MailFixtures.Pager(MailFixtures.messages(2, now), now), invalidated, now);
                context.getSharedPreferences("mail-settings-fixture", 0).edit().putString("saved", "preserve").commit();
                store.clear();
                assertEquals(0, old.count()); assertNull(old.row(0));
                try { invalidated.commit(pending); fail("A revoked generation republished mail"); }
                catch (java.io.IOException expected) { }
                assertEquals("preserve", context.getSharedPreferences("mail-settings-fixture", 0).getString("saved", ""));
            }
        }
    }

    @Test public void refusesTamperedPagesInsteadOfTreatingMissingDataAsComplete() throws Exception {
        MailFixtures.save(store, MailFixtures.messages(201, now), now);
        String token = store.metadata().getString("snapshot");
        File page = new File(new File(new File(context.getFilesDir(), "mail-widget"), token), "00001.page");
        byte[] bytes = Files.readAllBytes(page.toPath()); bytes[bytes.length - 1] ^= 1; Files.write(page.toPath(), bytes);
        try { store.reader(now); fail("Tampered page was accepted"); }
        catch (Exception expected) { store.markFailure("cache_error"); }
        assertEquals("cache_error", store.metadata().getString("refreshError"));
    }
}
