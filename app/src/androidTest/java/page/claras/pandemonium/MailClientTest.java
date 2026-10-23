package page.claras.pandemonium;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class MailClientTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @After public void clear() throws Exception { new HomeWidgetStore(context).clear(); }

    @Test public void onlyUsesViewingLinksFromTheLocalMailSection() throws Exception {
        JSONObject cache = fixture("content://org.kman.AquaMail.datax.messages/fixture/42");
        Intent intent = MailClientActivity.selectedIntent(cache, "42");
        assertNotNull(intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals(MailClientActivity.PACKAGE, intent.getPackage());
        assertNull(intent.getExtras());
        assertNull(MailClientActivity.selectedIntent(cache, "missing"));
        cache.getJSONObject("mail").remove("aquaMail");
        assertNull("Server summaries cannot inject app links", MailClientActivity.selectedIntent(cache, "42"));
        for (String link : new String[]{"mailto:fixture@example.test", "intent://fixture", "javascript:fixture", ""}) {
            assertNull(MailClientActivity.selectedIntent(fixture(link), "42"));
        }
    }

    @Test public void actualMailRouterDispatchesToAquaMailWithoutRenderingTheMessage() throws Exception {
        new HomeWidgetStore(context).save(fixture("content://org.kman.AquaMail.datax.messages/fixture/42"));
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<Intent> observed = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!MailClientActivity.PACKAGE.equals(intent.getPackage())) return null;
                observed.set(new Intent(intent)); dispatched.countDown();
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MailClientActivity.class)
                .putExtra("itemId", "42").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            assertTrue("Aqua Mail handoff observed", dispatched.await(10, TimeUnit.SECONDS));
            assertEquals(Intent.ACTION_VIEW, observed.get().getAction());
            assertEquals("content://org.kman.AquaMail.datax.messages/fixture/42", observed.get().getDataString());
            assertNull("No credentials or message contents in the handoff", observed.get().getExtras());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private JSONObject fixture(String uri) throws Exception {
        return new JSONObject().put("mail", new JSONObject().put("aquaMail", true).put("items", new JSONArray()
            .put(new JSONObject().put("messageId", "42").put("viewUri", uri).put("subject", "Private fixture"))));
    }
}
