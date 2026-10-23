package page.claras.pandemonium;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Aqua Mail's published read-only integration; each row keeps the provider's exact view URI. */
final class AquaMailSource {
    static final String READ_PERMISSION = "org.kman.AquaMail.datax.READ_MESSAGES";
    static final Uri UNREAD = Uri.parse("content://org.kman.AquaMail.datax.messages/smart/unread");
    static final String[] COLUMNS = {"_id", "header_subject", "header_from", "header_when", "preview", "view_uri"};

    static boolean needsPermission(Context context) {
        try {
            PermissionInfo info = context.getPackageManager().getPermissionInfo(READ_PERMISSION, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
                && context.checkSelfPermission(READ_PERMISSION) != PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    static JSONObject read(Context context) throws Exception {
        if (needsPermission(context)) return unavailable(true);
        CancellationSignal cancellation = new CancellationSignal();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable timeout = cancellation::cancel;
        handler.postDelayed(timeout, 5000);
        try { return read(context.getContentResolver(), cancellation); }
        catch (SecurityException denied) { return unavailable(needsPermission(context)); }
        catch (IllegalArgumentException | android.os.OperationCanceledException unavailable) { return unavailable(false); }
        finally { handler.removeCallbacks(timeout); }
    }

    static JSONObject read(ContentResolver resolver, CancellationSignal cancellation) throws Exception {
        try (Cursor cursor = resolver.query(UNREAD, COLUMNS, null, null, "header_when DESC", cancellation)) {
            if (cursor == null) return unavailable(false);
            List<JSONObject> rows = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int count = 0;
            while (cursor.moveToNext() && count++ < 1000) {
                cancellation.throwIfCanceled();
                String id = value(cursor, "_id", 128);
                String uri = value(cursor, "view_uri", 4096);
                if (id.isEmpty() || uri.isEmpty() || MailClientActivity.viewIntent(uri) == null) continue;
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate Aqua Mail message identity");
                long date = cursor.getLong(cursor.getColumnIndexOrThrow("header_when"));
                // Older provider versions can report Unix seconds; preserve the same instant in either unit.
                if (date > 0 && date < 100_000_000_000L) date *= 1000;
                Rfc822Token[] senders = Rfc822Tokenizer.tokenize(value(cursor, "header_from", 1000));
                JSONArray from = new JSONArray();
                if (senders.length > 0) from.put(new JSONObject().put("name", senders[0].getName())
                    .put("address", senders[0].getAddress()));
                rows.add(new JSONObject().put("messageId", "aqua:" + id).put("viewUri", uri)
                    .put("subject", value(cursor, "header_subject", 500)).put("from", from)
                    .put("date", Instant.ofEpochMilli(date).toString()).put("snippet", value(cursor, "preview", 1000)));
            }
            rows.sort(Comparator.comparing((JSONObject row) -> Instant.parse(row.optString("date"))).reversed());
            JSONArray items = new JSONArray();
            for (int i = 0; i < Math.min(30, rows.size()); i++) items.put(rows.get(i));
            return new JSONObject().put("aquaMail", true).put("items", items).put("updatedAt", System.currentTimeMillis());
        }
    }

    static JSONObject unavailable(boolean permission) throws Exception {
        return new JSONObject().put("aquaMail", true).put("items", new JSONArray())
            .put("unavailable", true).put("permissionRequired", permission);
    }

    private static String value(Cursor cursor, String column, int limit) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        if (value == null) return "";
        // Never truncate a locator into a different, potentially valid message link.
        if (value.length() > limit && ("view_uri".equals(column) || "_id".equals(column))) return "";
        return value.substring(0, Math.min(value.length(), limit));
    }
}
