package page.claras.pandemonium;

import android.content.Context;
import android.webkit.CookieManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Run seed and verify as separate instrumentation invocations, with force-stop between them. */
public class ProcessPersistenceTest {
    @Test public void survivesProcessDeath() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("persistencePhase");
        Assume.assumeTrue(phase != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecureSessionStore store = new SecureSessionStore(context);
        String fixture = "restart-fixture-123456789012345678901234567890";
        CookieManager cookies = CookieManager.getInstance();
        if ("seed".equals(phase)) {
            store.save(new JSONObject().put("refreshToken", fixture));
            CountDownLatch done = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> cookies.setCookie(SessionManager.ORIGIN,
                SessionManager.SESSION + "=" + fixture + "; Secure; HttpOnly; Path=/; Max-Age=604800", accepted -> done.countDown()));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            cookies.flush();
        } else {
            assertEquals("verify", phase);
            assertEquals(fixture, store.read().getString("refreshToken"));
            assertEquals(fixture, SessionManager.cookieValue(cookies.getCookie(SessionManager.ORIGIN), SessionManager.SESSION));
            store.clear();
            CountDownLatch done = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> cookies.removeAllCookies(removed -> done.countDown()));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            cookies.flush();
        }
    }
}
