package page.claras.pandemonium;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Hands mail taps to Aqua Mail without rendering or fetching a message body. */
public final class MailClientActivity extends Activity {
    static final String PACKAGE = "org.kman.AquaMail";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("itemId");
        if (id == null && AquaMailSource.needsPermission(this)) {
            requestPermissions(new String[]{AquaMailSource.READ_PERMISSION}, 1);
            return;
        }
        openMail(id);
    }

    private void openMail(String id) {
        JSONObject cache = new HomeWidgetStore(this).read();
        Intent selected = selectedIntent(cache, id);
        boolean opened = selected != null && open(selected);
        if (id == null) {
            Intent inbox = getPackageManager().getLaunchIntentForPackage(PACKAGE);
            opened = inbox != null && open(inbox);
        }
        if (!opened) Toast.makeText(this, id == null ? R.string.aquamail_unavailable : R.string.aquamail_message_unavailable, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1) {
            HomeWidgetJob.schedule(this, true);
            openMail(null);
        }
    }

    static Intent selectedIntent(JSONObject cache, String id) {
        JSONObject mail = cache.optJSONObject(HomeWidgetViews.MAIL);
        // Only the local Aqua Mail reader can mark this section; server summaries cannot supply app links.
        if (id == null || mail == null || !mail.optBoolean("aquaMail")) return null;
        JSONArray items = mail.optJSONArray("items");
        if (items == null) return null;
        for (int i = 0; i < Math.min(items.length(), 100); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !id.equals(item.optString("messageId"))) continue;
            String link = item.optString("viewUri");
            if (link.isEmpty() || link.length() > 4096) return null;
            return viewIntent(link);
        }
        return null;
    }

    static Intent viewIntent(String link) {
        if (link.isEmpty() || link.length() > 4096) return null;
        Uri uri = Uri.parse(link);
        // The locator comes from Aqua Mail's own provider; no compose or executable links are accepted.
        if (uri.getScheme() == null || "mailto".equalsIgnoreCase(uri.getScheme())
                || "intent".equalsIgnoreCase(uri.getScheme()) || "javascript".equalsIgnoreCase(uri.getScheme())) return null;
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE);
    }

    private boolean open(Intent intent) {
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }
}
