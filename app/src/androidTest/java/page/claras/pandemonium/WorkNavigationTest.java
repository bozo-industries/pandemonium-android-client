package page.claras.pandemonium;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Before public void before() throws Exception {
        new SecureSessionStore(context).clear();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        CountDownLatch cleared = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> android.webkit.CookieManager.getInstance().removeAllCookies(ignored -> cleared.countDown()));
        assertTrue(cleared.await(10, TimeUnit.SECONDS));
    }

    @Test public void workTapReusesTheOpenAppAndBackReturnsToItsPreviousPage() throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            WebView web = web(instrumentation, activity);
            assertUrl(instrumentation, activity, "https://claras.page/ui");
            instrumentation.runOnMainSync(() -> {
                web.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(
                            "<html><body>Navigation fixture</body></html>".getBytes(StandardCharsets.UTF_8)));
                    }
                });
                web.loadUrl("https://claras.page/finance/");
            });
            assertUrl(instrumentation, activity, "https://claras.page/finance/");
            instrumentation.runOnMainSync(() -> activity.startActivity(MainActivity.workIntent(context)));
            // Inspect the original instance: a second activity cannot change this WebView or its intent.
            assertUrl(instrumentation, activity, "https://claras.page/work/");
            instrumentation.runOnMainSync(() -> assertEquals(MainActivity.workIntent(context).getAction(), activity.getIntent().getAction()));
            instrumentation.runOnMainSync(activity::onBackPressed);
            assertUrl(instrumentation, activity, "https://claras.page/finance/");
            assertFalse(activity.isFinishing());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void coldWorkIntentOverridesLastPageAndRecreationRestoresNavigation() throws Exception {
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putString("lastPath", "/finance/").commit();
        ActivityLifecycleCallback fixture = (created, stage) -> {
            if (stage != Stage.CREATED || !(created instanceof MainActivity)) return;
            WebView createdWeb = find(created.getWindow().getDecorView());
            assertNotNull(createdWeb);
            createdWeb.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(
                        "<html><body>Navigation fixture</body></html>".getBytes(StandardCharsets.UTF_8)));
                }
            });
        };
        instrumentation.runOnMainSync(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(fixture));
        Activity activity = null;
        try {
            activity = instrumentation.startActivitySync(MainActivity.workIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertUrl(instrumentation, activity, "https://claras.page/work/");
            WebView web = web(instrumentation, activity);
            instrumentation.runOnMainSync(() -> web.loadUrl("https://claras.page/finance/"));
            assertUrl(instrumentation, activity, "https://claras.page/finance/");
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(activity::recreate);
                Activity restored = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(restored);
                activity = restored;
                assertUrl(instrumentation, restored, "https://claras.page/finance/");
            } finally { instrumentation.removeMonitor(monitor); }
        } finally {
            Activity done = activity;
            instrumentation.runOnMainSync(() -> {
                if (done != null) done.finish();
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(fixture);
            });
            context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void assertUrl(Instrumentation instrumentation, Activity activity, String expected) throws Exception {
        waitForUrl(instrumentation, activity, expected, true);
    }

    static void assertRequestedUrl(Instrumentation instrumentation, Activity activity, String expected) throws Exception {
        waitForUrl(instrumentation, activity, expected, false);
    }

    private static void waitForUrl(Instrumentation instrumentation, Activity activity, String expected, boolean requireCommit) throws Exception {
        WebView web = web(instrumentation, activity);
        AtomicReference<String> actual = new AtomicReference<>();
        AtomicReference<Boolean> committed = new AtomicReference<>(false);
        AtomicReference<String> witness = new AtomicReference<>();
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                actual.set(web.getUrl());
                android.webkit.WebHistoryItem current = web.copyBackForwardList().getCurrentItem();
                witness.set("progress=" + web.getProgress() + ", historyUrl=" + (current == null ? "none" : current.getUrl())
                    + ", historyOriginal=" + (current == null ? "none" : current.getOriginalUrl()));
                committed.set(!requireCommit || (web.getProgress() == 100 && current != null && expected.equals(current.getUrl())));
            });
            if (expected.equals(actual.get()) && committed.get()) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail("Navigation did not " + (requireCommit ? "commit " : "request ") + expected + "; observed " + actual.get() + "; " + witness.get());
    }

    private static WebView web(Instrumentation instrumentation, Activity activity) {
        AtomicReference<WebView> found = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> found.set(find(activity.getWindow().getDecorView())));
        assertNotNull(found.get());
        return found.get();
    }

    private static WebView find(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            WebView found = find(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
