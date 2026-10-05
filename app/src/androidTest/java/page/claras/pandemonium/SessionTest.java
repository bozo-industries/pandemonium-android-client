package page.claras.pandemonium;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SessionTest {
    private static final String CLIENT = "fixture-client-123456789012345";
    private static final String ACCESS = "fixture-access-1234567890123456789012345678901";
    private Context context;
    private SecureSessionStore store;

    @Before public void reset() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new SecureSessionStore(context);
        store.clear();
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> CookieManager.getInstance().removeAllCookies(removed -> done.countDown()));
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @Test public void keepsNativeStateEncryptedAcrossStoreRecreation() throws Exception {
        String secret = "fixture-refresh-secret";
        store.save(new JSONObject().put("refreshToken", secret));
        assertEquals(secret, new SecureSessionStore(context).read().getString("refreshToken"));
        assertFalse(context.getSharedPreferences("native-session", 0).getString("encrypted", "").contains(secret));
        store.clear();
        assertNull(store.read());
    }

    @Test public void enrollsOnceAndKeepsTheGrantWhileAccessIsFresh() throws Exception {
        seedCookies();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionManager manager = new SessionManager(context, (endpoint, cookie, body) -> {
            calls.incrementAndGet();
            assertEquals("enroll", endpoint);
            assertTrue(cookie.contains(SessionManager.SESSION + "="));
            assertTrue(body.getString("refreshToken").matches("[A-Za-z0-9_-]{43}"));
            assertEquals(body.getString("refreshToken"), store.read().getString("refreshToken"));
            return success(false);
        });
        assertEquals(SessionManager.Result.READY, sync(manager));
        assertEquals(SessionManager.Result.READY, sync(manager));
        assertEquals(1, calls.get());
        assertFalse(store.read().has("enrolling"));
    }

    @Test public void retriesTheExactPersistedRotationAfterLostResponse() throws Exception {
        seedCookies();
        assertEquals(SessionManager.Result.READY, sync(new SessionManager(context, (endpoint, cookie, body) -> success(false))));
        JSONObject expired = store.read().put("expiresAt", System.currentTimeMillis() - 1);
        store.save(expired);
        AtomicReference<String> proposal = new AtomicReference<>();
        SessionManager disconnected = new SessionManager(context, (endpoint, cookie, body) -> {
            assertEquals("refresh", endpoint);
            assertFalse(cookie.contains(SessionManager.SESSION));
            proposal.set(body.getString("nextToken"));
            assertEquals(proposal.get(), store.read().getString("nextToken"));
            throw new java.io.IOException("fixture lost response");
        });
        assertEquals(SessionManager.Result.UNAVAILABLE, sync(disconnected));
        assertEquals(expired.getString("refreshToken"), store.read().getString("refreshToken"));
        SessionManager recreated = new SessionManager(context, (endpoint, cookie, body) -> {
            assertEquals(proposal.get(), body.getString("nextToken"));
            return success(true);
        });
        assertEquals(SessionManager.Result.RELOAD, sync(recreated));
        assertEquals(proposal.get(), store.read().getString("refreshToken"));
        assertFalse(store.read().has("nextToken"));
        assertEquals("renewed-access-1234567890123456789012345678901", SessionManager.cookieValue(CookieManager.getInstance().getCookie(SessionManager.ORIGIN), SessionManager.SESSION));
    }

    @Test public void doesNotSilentlySignBackInAfterWebsiteLogout() throws Exception {
        seedCookies();
        sync(new SessionManager(context, (endpoint, cookie, body) -> success(false)));
        setCookie(SessionManager.SESSION + "=; Path=/; Secure; HttpOnly; Max-Age=0");
        SessionManager manager = new SessionManager(context, (endpoint, cookie, body) -> { throw new AssertionError("Logout must not refresh"); });
        assertEquals(SessionManager.Result.READY, sync(manager));
        assertNull(store.read());
    }

    @Test public void rejectsRevokedGrantsAndClearsAccessCookies() throws Exception {
        seedCookies();
        sync(new SessionManager(context, (endpoint, cookie, body) -> success(false)));
        store.save(store.read().put("expiresAt", System.currentTimeMillis() - 1));
        SessionManager manager = new SessionManager(context, (endpoint, cookie, body) -> new SessionManager.Reply(401, new JSONObject(), Collections.emptyList()));
        assertEquals(SessionManager.Result.RELOAD, sync(manager));
        assertNull(store.read());
        assertNull(SessionManager.cookieValue(CookieManager.getInstance().getCookie(SessionManager.ORIGIN), SessionManager.SESSION));
    }

    @Test public void acceptsOnlyTheExactHttpsOriginInsideTheApp() {
        assertTrue(MainActivity.internal(Uri.parse("https://claras.page/work/")));
        assertTrue(MainActivity.internal(Uri.parse("https://claras.page:443/finance/")));
        for (String url : Arrays.asList("http://claras.page/", "https://claras.page.evil.test/", "https://evil.test/", "https://claras.page:444/", "https://someone@claras.page/", "file:///data/", "javascript:alert(1)")) {
            assertFalse(url, MainActivity.internal(Uri.parse(url)));
        }
    }

    private SessionManager.Reply success(boolean rotated) throws Exception {
        long now = System.currentTimeMillis();
        return new SessionManager.Reply(200, new JSONObject().put("ok", true).put("expiresAt", now + TimeUnit.DAYS.toMillis(7)).put("refreshExpiresAt", now + TimeUnit.DAYS.toMillis(90)), rotated ? Arrays.asList(
            SessionManager.SESSION + "=renewed-access-1234567890123456789012345678901; Path=/; Secure; HttpOnly; Max-Age=604800",
            SessionManager.CLIENT + "=" + CLIENT + "; Path=/; Secure; HttpOnly; Max-Age=604800") : Collections.emptyList());
    }

    private SessionManager.Result sync(SessionManager manager) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<SessionManager.Result> result = new AtomicReference<>();
        manager.sync(value -> { result.set(value); done.countDown(); });
        assertTrue("Session operation timed out", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    private void seedCookies() throws Exception {
        setCookie(SessionManager.SESSION + "=" + ACCESS + "; Path=/; Secure; HttpOnly; Max-Age=604800");
        setCookie(SessionManager.CLIENT + "=" + CLIENT + "; Path=/; Secure; HttpOnly; Max-Age=604800");
    }

    private void setCookie(String value) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> CookieManager.getInstance().setCookie(SessionManager.ORIGIN, value, accepted -> done.countDown()));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        CookieManager.getInstance().flush();
    }
}
