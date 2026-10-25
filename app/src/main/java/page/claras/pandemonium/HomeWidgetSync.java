package page.claras.pandemonium;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static void fetch(Context app, long requestGeneration, boolean serverAvailable) {
        try {
            HomeWidgetStore store = new HomeWidgetStore(app);
            JSONObject cache = store.read();
            MailWidgetStore mailStore = new MailWidgetStore(app);
            boolean mailInstalled = HomeWidgetProvider.ids(app, MailWidgetProvider.class).length > 0;
            if (!serverAvailable) {
                synchronized (LOCK) {
                    if (generation != requestGeneration) return;
                    if (mailInstalled) mailStore.markFailure("unavailable");
                    JSONObject work = cache.optJSONObject(HomeWidgetViews.CALENDAR);
                    if (work != null) { work.put("refreshFailed", true); store.save(cache); }
                }
                return;
            }
            String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN);
            if (SessionManager.cookieValue(cookie, SessionManager.SESSION) == null) {
                cache.put("signedOut", true).remove(HomeWidgetViews.CALENDAR);
                cache.remove(HomeWidgetViews.MAIL);
                synchronized (LOCK) { if (generation == requestGeneration) { mailStore.clear(); store.save(cache); } }
                return;
            }
            cache.put("signedOut", false);
            synchronized (LOCK) {
                if (generation != requestGeneration) return;
                store.save(cache);
            }
            boolean success = false;
            if (mailInstalled) {
                try (MailWidgetStore.Writer writer = mailStore.writer()) {
                    JSONObject mail = MailWindowSource.read(params -> HomeWidgetApi.call(app, MailWindowSource.METHOD, params), writer, Instant.now());
                    synchronized (LOCK) {
                        if (generation != requestGeneration) return;
                        writer.commit(mail);
                        success = true;
                        cache.remove(HomeWidgetViews.MAIL);
                        store.save(cache);
                    }
                } catch (HomeWidgetApi.Unauthorized unauthorized) {
                    cache.put("signedOut", true).remove(HomeWidgetViews.CALENDAR);
                    cache.remove(HomeWidgetViews.MAIL);
                    synchronized (LOCK) { if (generation == requestGeneration) { mailStore.clear(); store.save(cache); } }
                    return;
                } catch (Exception error) {
                    synchronized (LOCK) {
                        if (generation != requestGeneration) return;
                        mailStore.markFailure(mailFailure(error));
                    }
                }
            }
            for (String kind : new String[]{HomeWidgetViews.CALENDAR}) {
                try {
                    JSONObject reply = HomeWidgetApi.call(app, WorkWeekSource.METHOD, new JSONObject());
                    JSONObject section = WorkWeekSource.read(reply, Instant.now());
                    synchronized (LOCK) {
                        if (generation != requestGeneration) return;
                        cache.put(kind, section); store.save(cache);
                    }
                    success = true;
                } catch (HomeWidgetApi.Unauthorized error) {
                    cache.put("signedOut", true).remove(HomeWidgetViews.CALENDAR);
                    cache.remove(HomeWidgetViews.MAIL);
                    synchronized (LOCK) { if (generation == requestGeneration) { mailStore.clear(); store.save(cache); } }
                    return;
                }
                catch (Exception ignored) {
                    synchronized (LOCK) {
                        if (generation != requestGeneration) return;
                        JSONObject section = cache.optJSONObject(kind);
                        if (section == null) section = new JSONObject();
                        section.put("refreshFailed", true);
                        cache.put(kind, section); store.save(cache);
                    }
                }
            }
            if (success) synchronized (LOCK) {
                if (generation == requestGeneration) nextAttempt = android.os.SystemClock.elapsedRealtime() + 15 * 60_000L;
            }
        } catch (Exception ignored) { /* Never log mailbox contents or credentials. */ }
        finally { finish(app); }
    }

    static void clear(Context context) throws Exception {
        try {
            synchronized (LOCK) {
                generation++;
                nextAttempt = 0;
                try { new MailWidgetStore(context).clear(); }
                finally { new HomeWidgetStore(context).clear(); }
            }
        } finally {
            HomeWidgetProvider.updateAll(context);
        }
    }

    static String mailFailure(Exception error) {
        if (error instanceof MailWindowSource.CursorExpired) return "expired";
        if (error instanceof MailWindowSource.LimitExceeded) return "limit";
        if (error instanceof MailWindowSource.InvalidSnapshot) return "incomplete";
        return "unavailable";
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
