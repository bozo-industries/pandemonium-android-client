package page.claras.pandemonium;

import android.content.Context;
import android.webkit.CookieManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class MailWidgetApiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void before() throws Exception { clearCookies(); }
    @After public void after() throws Exception { clearCookies(); }

    @Test public void sendsOnlyThePagedReadThroughTheExistingAuthenticatedRpcBoundary() throws Exception {
        authenticate();
        JSONObject page = new MailFixtures.Pager(new org.json.JSONArray(), Instant.now()).call(new JSONObject().put("limit", 100));
        FakeConnection connection = new FakeConnection(new JSONObject().put("result", page).toString());
        JSONObject params = new JSONObject().put("limit", 100).put("cursor", MailFixtures.cursor(100));
        JSONObject result = HomeWidgetApi.call(context, MailWindowSource.METHOD, params, url -> {
            assertEquals("https://claras.page/api/rpc", url.toString()); return connection;
        });
        assertTrue(result.has("coverage"));
        JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
        assertEquals("email.widget.mail", request.getString("method"));
        assertEquals(2, request.getJSONObject("params").length());
        assertEquals(MailFixtures.cursor(100), request.getJSONObject("params").getString("cursor"));
        assertFalse(request.toString().contains("refresh")); assertFalse(request.toString().contains("index"));
        assertEquals("POST", connection.getRequestMethod());
        assertTrue(connection.getRequestProperty("Cookie").contains("fixture-mail-session"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }

    @Test public void rejectsLegacyBodyReadsAndMissingAuthenticationBeforeOpeningAnyConnection() throws Exception {
        HomeWidgetApi.ConnectionFactory forbidden = url -> { throw new AssertionError("Unexpected connection"); };
        for (String method : new String[]{"email.widget.inbox", "email.widget.message", "email.background.sync", "email.messages.read"}) {
            try { HomeWidgetApi.call(context, method, new JSONObject(), forbidden); fail("Unsupported mailbox operation admitted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { HomeWidgetApi.call(context, MailWindowSource.METHOD, new JSONObject().put("limit", 100), forbidden); fail("Unauthenticated read opened"); }
        catch (HomeWidgetApi.Unauthorized expected) { }
    }

    @Test public void keepsExpiredLimitAndOversizedResponsesAsExplicitFailedReads() throws Exception {
        authenticate();
        for (String kind : new String[]{"expired", "limit", "oversized", "type", "unauthorized"}) {
            String message = kind.equals("expired") ? "email.widget.mail cursor is invalid or expired"
                : "email.widget.mail pagination exceeds the explicit 50000-message safety bound";
            FakeConnection connection = new FakeConnection(new JSONObject().put("error", new JSONObject().put("message", message)).toString());
            if (kind.equals("oversized")) connection.body = new byte[1_048_577];
            if (kind.equals("type")) connection.type = "text/html";
            if (kind.equals("unauthorized")) connection.status = 401;
            try { HomeWidgetApi.call(context, MailWindowSource.METHOD, new JSONObject().put("limit", 100), url -> connection); fail("Failed response completed: " + kind); }
            catch (Exception error) {
                if (kind.equals("expired")) assertTrue(error instanceof MailWindowSource.CursorExpired);
                if (kind.equals("limit")) assertTrue(error instanceof MailWindowSource.LimitExceeded);
                if (kind.equals("unauthorized")) assertTrue(error instanceof HomeWidgetApi.Unauthorized);
                if (kind.equals("oversized") || kind.equals("type")) assertTrue(error instanceof java.io.IOException);
                assertTrue(connection.disconnected);
            }
        }
    }

    private void authenticate() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> CookieManager.getInstance().setCookie(SessionManager.ORIGIN,
            SessionManager.SESSION + "=fixture-mail-session; Path=/; Secure; HttpOnly", accepted -> ready.countDown()));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
    }
    private void clearCookies() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> CookieManager.getInstance().removeAllCookies(ignored -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] body; boolean disconnected; int status = 200; String type = "application/json; charset=utf-8";
        FakeConnection(String body) throws Exception { super(new URL("https://claras.page/api/rpc")); this.body = body.getBytes(StandardCharsets.UTF_8); }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return type; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getOutputStream() { return output; }
    }
}
