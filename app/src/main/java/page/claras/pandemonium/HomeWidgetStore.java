package page.claras.pandemonium;

import android.content.Context;
import org.json.JSONObject;

/** Mail summaries and agenda entries never leave the encrypted app-private cache. */
final class HomeWidgetStore {
    private static final Object LOCK = new Object();
    private final SecureSessionStore store;

    HomeWidgetStore(Context context) {
        store = new SecureSessionStore(context, "home-widgets", "pandemonium.home-widgets.v1");
    }

    JSONObject read() {
        synchronized (LOCK) {
            try { JSONObject value = store.read(); return value == null ? new JSONObject() : value; }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    void save(JSONObject state) throws Exception {
        synchronized (LOCK) {
            if (state.toString().length() > 512_000) throw new java.io.IOException("Widget cache too large");
            store.save(state);
        }
    }

    void clear() throws Exception { synchronized (LOCK) { store.clear(); } }
}
