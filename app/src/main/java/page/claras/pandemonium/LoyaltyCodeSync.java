package page.claras.pandemonium;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class LoyaltyCodeSync {
    private static final String[] PROVIDERS = {LoyaltyCodeStore.REWE, LoyaltyCodeStore.LIDL, LoyaltyCodeStore.ACTION};
    private static final long RETRY_INTERVAL_MS = 60_000L;
    private static final long SUCCESS_INTERVAL_MS = 15 * 60_000L;
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final ExecutorService REQUESTS = Executors.newFixedThreadPool(3);
    private static final List<Runnable> COMPLETIONS = new ArrayList<>();
    private static boolean running;
    private static long nextAttemptAt;
    private static long generation;

    private LoyaltyCodeSync() { }

    static void refreshIfWidgetExists(Context context, boolean force) {
        Context app = context.getApplicationContext();
        int[] ids = AppWidgetManager.getInstance(app).getAppWidgetIds(new ComponentName(app, LoyaltyWidgetProvider.class));
        if (ids.length > 0) refresh(app, force, null);
    }

    static void refresh(Context context, boolean force, Runnable completion) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (completion != null) COMPLETIONS.add(completion);
            if (running) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (!force && now < nextAttemptAt) {
                runCompletionsLocked();
                return;
            }
            running = true;
            nextAttemptAt = now + RETRY_INTERVAL_MS;
            long requestGeneration = generation;
            WORKER.execute(() -> refreshOnWorker(app, requestGeneration));
        }
    }

    static void clearCodes(Context context, LoyaltyCodeStore store) throws Exception {
        synchronized (LOCK) {
            generation++;
            store.clear();
        }
        LoyaltyWidgetProvider.updateAll(context.getApplicationContext());
    }

    private static void refreshOnWorker(Context context, long requestGeneration) {
        boolean receivedCurrentCards = false;
        try {
            String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN + "/finance/");
            if (!hasSession(cookie)) return;
            String userAgent = WebSettings.getDefaultUserAgent(context);
            LoyaltyCodeStore store = new LoyaltyCodeStore(context);
            Map<String, String> payloads = new HashMap<>(store.read());
            List<Future<JSONObject>> requests = new ArrayList<>();
            for (String provider : PROVIDERS) {
                requests.add(REQUESTS.submit(() -> LoyaltyWalletClient.getCard(provider, cookie, userAgent)));
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 5_500L;
            for (int i = 0; i < PROVIDERS.length; i++) {
                JSONObject card = null;
                try {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining > 0) card = requests.get(i).get(remaining, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    requests.get(i).cancel(true);
                }
                if (card != null) receivedCurrentCards |= LoyaltyWalletClient.mergeCard(PROVIDERS[i], card, payloads);
            }
            if (receivedCurrentCards) {
                synchronized (LOCK) {
                    if (requestGeneration == generation) store.replace(payloads);
                }
            }
        } catch (Exception ignored) {
            // Keep the last encrypted static passes on network, session, or storage errors.
        } finally {
            if (receivedCurrentCards) {
                synchronized (LOCK) { nextAttemptAt = android.os.SystemClock.elapsedRealtime() + SUCCESS_INTERVAL_MS; }
            }
            LoyaltyWidgetProvider.updateAll(context);
            synchronized (LOCK) {
                running = false;
                runCompletionsLocked();
            }
        }
    }

    private static boolean hasSession(String cookie) {
        return SessionManager.cookieValue(cookie, SessionManager.SESSION) != null
            && SessionManager.cookieValue(cookie, SessionManager.CLIENT) != null;
    }

    private static void runCompletionsLocked() {
        List<Runnable> completed = new ArrayList<>(COMPLETIONS);
        COMPLETIONS.clear();
        for (Runnable callback : completed) {
            try { callback.run(); } catch (RuntimeException ignored) { }
        }
    }
}
