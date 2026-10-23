package page.claras.pandemonium;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class HomeWidgetSync {
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final List<Runnable> COMPLETIONS = new ArrayList<>();
    private static boolean running;
    private static long generation, nextAttempt;
    static boolean isRunning() { synchronized (LOCK) { return running; } }

    static void refreshIfWidgetsExist(Context context, boolean force) {
        if (HomeWidgetProvider.any(context)) refresh(context, force, null);
    }

    static void refresh(Context context, boolean force, Runnable completion) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (completion != null) COMPLETIONS.add(completion);
            if (running) return;
            if (!force && android.os.SystemClock.elapsedRealtime() < nextAttempt) { complete(); return; }
            running = true;
            nextAttempt = android.os.SystemClock.elapsedRealtime() + 60_000L;
        }
        new Handler(Looper.getMainLooper()).post(() -> SessionManager.get(app).sync(result -> {
            final long requestGeneration;
            synchronized (LOCK) { requestGeneration = generation; }
            WORKER.execute(() -> fetch(app, requestGeneration, result != SessionManager.Result.UNAVAILABLE));
        }));
    }

    private static void fetch(Context app, long requestGeneration, boolean calendarAvailable) {
        try {
            HomeWidgetStore store = new HomeWidgetStore(app);
            JSONObject cache = store.read();
            if (HomeWidgetProvider.ids(app, MailWidgetProvider.class).length > 0) {
                JSONObject mail;
                try { mail = AquaMailSource.read(app); }
                catch (Exception unavailable) { mail = AquaMailSource.unavailable(false); }
                cache.put(HomeWidgetViews.MAIL, mail);
            }
            synchronized (LOCK) {
                if (generation != requestGeneration) return;
                store.save(cache);
            }
            if (!calendarAvailable) return;
            String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN);
            if (SessionManager.cookieValue(cookie, SessionManager.SESSION) == null) {
                cache.put("signedOut", true).remove(HomeWidgetViews.CALENDAR);
                synchronized (LOCK) { if (generation == requestGeneration) store.save(cache); }
                return;
            }
            cache.put("signedOut", false);
            synchronized (LOCK) {
                if (generation != requestGeneration) return;
                store.save(cache);
            }
            boolean success = false;
            for (String kind : new String[]{HomeWidgetViews.CALENDAR}) {
                // A detail screen can request a refresh even before a widget is installed.
                try {
                    JSONObject params = new JSONObject();
                    LocalDate today = LocalDate.now();
                    params.put("startDay", today.toString()).put("endDay", today.plusDays(31).toString());
                    JSONObject reply = HomeWidgetApi.call(app, "calendar.widget.agenda", params);
                    JSONArray items = reply.optJSONArray("events");
                    if (items == null) continue;
                    JSONObject section = new JSONObject().put("items", items).put("updatedAt", System.currentTimeMillis());
                    synchronized (LOCK) {
                        if (generation != requestGeneration) return;
                        cache.put(kind, section); store.save(cache);
                    }
                    success = true;
                } catch (HomeWidgetApi.Unauthorized error) {
                    cache.put("signedOut", true).remove(HomeWidgetViews.CALENDAR);
                    synchronized (LOCK) { if (generation == requestGeneration) store.save(cache); }
                    return;
                }
                catch (Exception ignored) { /* Preserve the other section and its last successful snapshot. */ }
            }
            if (success) synchronized (LOCK) { nextAttempt = android.os.SystemClock.elapsedRealtime() + 15 * 60_000L; }
        } catch (Exception ignored) { /* Never log mailbox contents or credentials. */ }
        finally { finish(app); }
    }

    static void clear(Context context) throws Exception {
        synchronized (LOCK) {
            generation++;
            nextAttempt = 0;
            new HomeWidgetStore(context).clear();
        }
        HomeWidgetProvider.updateAll(context);
    }

    private static void finish(Context context) {
        HomeWidgetProvider.updateAll(context);
        synchronized (LOCK) { running = false; complete(); }
    }
    private static void complete() {
        List<Runnable> callbacks = new ArrayList<>(COMPLETIONS); COMPLETIONS.clear();
        Handler main = new Handler(Looper.getMainLooper());
        for (Runnable callback : callbacks) main.post(callback);
    }
}
