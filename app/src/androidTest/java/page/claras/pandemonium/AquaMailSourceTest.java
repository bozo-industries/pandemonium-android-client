package page.claras.pandemonium;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** API 35 fixture exercises the actual provider reader without claiming an installed Aqua Mail session. */
@TargetApi(29)
public class AquaMailSourceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void readsUnreadSummariesAndPreservesTheProvidersExactMessageLinks() throws Exception {
        MatrixCursor cursor = new MatrixCursor(AquaMailSource.COLUMNS);
        cursor.addRow(new Object[]{"7", "Older", "Delivery <delivery@example.test>", 1792750000L, "Preview", "content://fixture/mail/7"});
        cursor.addRow(new Object[]{"8", "Newer", "Sender <sender@example.test>", 1792750060000L, "Preview 2", "content://fixture/mail/8"});
        cursor.addRow(new Object[]{"9", "Invalid", "sender@example.test", 1792750060000L, "Ignored", "mailto:other@example.test"});
        ContentResolver resolver = resolver(cursor, null);
        JSONObject section = AquaMailSource.read(resolver, new CancellationSignal());
        assertTrue(cursor.isClosed());
        assertTrue(section.getBoolean("aquaMail"));
        JSONArray items = section.getJSONArray("items");
        assertEquals(2, items.length());
        assertEquals("aqua:8", items.getJSONObject(0).getString("messageId"));
        assertEquals("content://fixture/mail/8", items.getJSONObject(0).getString("viewUri"));
        assertEquals("sender@example.test", items.getJSONObject(0).getJSONArray("from").getJSONObject(0).getString("address"));
        assertFalse(items.getJSONObject(0).has("body_text"));
        assertEquals("content://fixture/mail/7", items.getJSONObject(1).getString("viewUri"));
    }

    @Test public void refusesDuplicateIdentitiesAndClosesTheProviderCursor() throws Exception {
        MatrixCursor cursor = new MatrixCursor(AquaMailSource.COLUMNS);
        for (int i = 0; i < 2; i++) cursor.addRow(new Object[]{"7", "Subject", "sender@example.test", 1792750000000L, "Preview", "content://fixture/mail/" + i});
        try { AquaMailSource.read(resolver(cursor, null), new CancellationSignal()); fail("Duplicate identity accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(cursor.isClosed()); }
    }

    @Test public void deniedOrUnavailableProvidersProduceNoMisleadingCachedMessages() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new SecurityException("denied"), new IllegalArgumentException("absent")}) {
            ContentResolver resolver = resolver(null, failure);
            Context wrapped = new ContextWrapper(context) {
                @Override public ContentResolver getContentResolver() { return resolver; }
            };
            JSONObject section = AquaMailSource.read(wrapped);
            assertTrue(section.getBoolean("unavailable"));
            assertEquals(0, section.getJSONArray("items").length());
        }
    }

    private ContentResolver resolver(MatrixCursor cursor, RuntimeException failure) {
        return ContentResolver.wrap(new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
                assertEquals(AquaMailSource.UNREAD, uri);
                assertArrayEquals(new String[]{"_id", "header_subject", "header_from", "header_when", "preview", "view_uri"}, projection);
                assertNull(selection); assertNull(args); assertEquals("header_when DESC", order);
                if (failure != null) throw failure;
                return cursor;
            }
            @Override public String getType(Uri uri) { return null; }
            @Override public Uri insert(Uri uri, ContentValues values) { throw new AssertionError("Mail integration must be read-only"); }
            @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError("Mail integration must be read-only"); }
            @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new AssertionError("Mail integration must be read-only"); }
        });
    }
}
