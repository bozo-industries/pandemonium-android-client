package page.claras.pandemonium;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the native grant; there is deliberately no JavaScript bridge. */
final class SessionManager {
    static final String ORIGIN = "https://claras.page";
    static final String SESSION = "pandemonium_site_session";
    static final String CLIENT = "pandemonium_site_client";
    enum Result { READY, RELOAD, UNAVAILABLE }
    interface Callback { void complete(Result result); }
    interface Transport { Reply post(String endpoint, String cookie, JSONObject body) throws Exception; }
    static final class Reply {
        final int status;
        final JSONObject body;
        final List<String> cookies;
        Reply(int status, JSONObject body, List<String> cookies) { this.status = status; this.body = body; this.cookies = cookies; }
    }
    private static SessionManager instance;
    private final SecureSessionStore store;
    private final Transport transport;
    private final CookieManager cookies = CookieManager.getInstance();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();
    private boolean running;

    static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext(), new SessionTransport(WebSettings.getDefaultUserAgent(context)));
        return instance;
    }

    SessionManager(Context context, Transport transport) {
        store = new SecureSessionStore(context);
        this.transport = transport;
    }

    synchronized void sync(Callback callback) {
        callbacks.add(callback);
        if (running) return;
        running = true;
        worker.execute(() -> {
            Result result;
            try { result = reconcile(); }
            catch (Exception error) { result = Result.UNAVAILABLE; } // Never log credentials or private HTTP responses.
            final Result completed = result;
            main.post(() -> {
                List<Callback> waiting;
                synchronized (SessionManager.this) {
                    waiting = new ArrayList<>(callbacks);
                    callbacks.clear();
                    running = false;
                }
                for (Callback item : waiting) item.complete(completed);
            });
        });
    }

    private Result reconcile() throws Exception {
        String cookie = cookies.getCookie(ORIGIN);
        String session = cookieValue(cookie, SESSION);
        String client = cookieValue(cookie, CLIENT);
        JSONObject state = store.read();
        long now = System.currentTimeMillis();
        if (state != null && !state.has("nextToken") && !state.optBoolean("enrolling")) {
            // A fresh website login starts a new grant. An explicit website logout must stay logged out.
            if ((session != null && !hash(session).equals(state.optString("sessionHash")))
                    || (session == null && now < state.optLong("expiresAt") - 60_000)) {
                store.clear();
                state = null;
            }
        }
        if (state == null) {
            if (session == null || client == null) return Result.READY;
            state = new JSONObject().put("refreshToken", randomToken()).put("clientId", client)
                .put("sessionHash", hash(session)).put("enrolling", true);
            store.save(state);
        }
        boolean enrolling = state.optBoolean("enrolling");
        if (enrolling && (session == null || !hash(session).equals(state.optString("sessionHash")))) {
            store.clear();
            return Result.READY;
        }
        if (!enrolling && !state.has("nextToken") && state.optLong("expiresAt") - now > TimeUnit.DAYS.toMillis(1)) return Result.READY;
        boolean expired = state.optLong("expiresAt") <= now;
        JSONObject body = new JSONObject().put("refreshToken", state.getString("refreshToken"));
        if (enrolling) {
            cookie = SESSION + "=" + session + "; " + CLIENT + "=" + client;
        } else {
            if (!state.has("nextToken")) {
                state.put("nextToken", randomToken());
                store.save(state); // A crash or lost response can now retry the exact rotation safely.
            }
            body.put("nextToken", state.getString("nextToken"));
            cookie = CLIENT + "=" + state.getString("clientId");
        }
        Reply reply = transport.post(enrolling ? "enroll" : "refresh", cookie, body);
        if (reply.status == 401) {
            store.clear();
            if (!java.util.Objects.equals(session, cookieValue(cookies.getCookie(ORIGIN), SESSION))) return Result.READY;
            installCookies(java.util.Arrays.asList(
                SESSION + "=; Path=/; Secure; HttpOnly; Max-Age=0",
                CLIENT + "=; Path=/; Secure; HttpOnly; Max-Age=0"));
            return Result.RELOAD;
        }
        if (reply.status != 200) return Result.UNAVAILABLE;
        JSONObject response = reply.body;
        if (!response.optBoolean("ok") || response.optLong("expiresAt") <= now || response.optLong("refreshExpiresAt") <= now) return Result.UNAVAILABLE;
        if (!enrolling) {
            if (reply.cookies.size() != 2
                || reply.cookies.stream().noneMatch(value -> cookieValue(value, SESSION) != null)
                || reply.cookies.stream().noneMatch(value -> cookieValue(value, CLIENT) != null)) return Result.UNAVAILABLE;
            installCookies(reply.cookies);
            session = cookieValue(cookies.getCookie(ORIGIN), SESSION);
            if (session == null) return Result.UNAVAILABLE;
            state.put("refreshToken", state.getString("nextToken"));
            state.remove("nextToken");
        }
        state.remove("enrolling");
        state.put("sessionHash", hash(session)).put("expiresAt", response.getLong("expiresAt"))
            .put("refreshExpiresAt", response.getLong("refreshExpiresAt"));
        store.save(state);
        return !enrolling && expired ? Result.RELOAD : Result.READY;
    }

    private void installCookies(List<String> values) throws Exception {
        CountDownLatch done = new CountDownLatch(values.size());
        java.util.concurrent.atomic.AtomicBoolean rejected = new java.util.concurrent.atomic.AtomicBoolean();
        main.post(() -> {
            for (String value : values) cookies.setCookie(ORIGIN, value, accepted -> { if (!accepted) rejected.set(true); done.countDown(); });
        });
        if (!done.await(20, TimeUnit.SECONDS) || rejected.get()) throw new java.io.IOException("Cookie persistence failed");
        cookies.flush();
    }

    static String cookieValue(String cookie, String name) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String value = part.trim();
            if (value.startsWith(name + "=")) {
                String token = value.substring(name.length() + 1);
                return token.matches("[A-Za-z0-9_-]{20,64}") ? token : null;
            }
        }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String hash(String value) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
}
