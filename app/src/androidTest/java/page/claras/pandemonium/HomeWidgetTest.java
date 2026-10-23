package page.claras.pandemonium;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeWidgetTest {
    private Context context;
    private Instrumentation instrumentation;
    @Before public void before() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        new SecureSessionStore(context).clear(); new HomeWidgetStore(context).clear();
        CountDownLatch cleared = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> CookieManager.getInstance().removeAllCookies(ignored -> cleared.countDown()));
        assertTrue(cleared.await(10, TimeUnit.SECONDS));
        HomeWidgetJob.cancel(context);
    }
    @After public void after() throws Exception {
        new HomeWidgetStore(context).clear(); new LoyaltyCodeStore(context).clear(); HomeWidgetJob.cancel(context);
    }

    @Test public void encryptsPersonalSummariesAndKeepsThemAcrossStoreRecreation() throws Exception {
        new HomeWidgetStore(context).save(fixture());
        assertEquals("Parcel ready", HomeWidgetViews.items(new HomeWidgetStore(context).read(), "mail").getJSONObject(0).getString("subject"));
        String disk = context.getSharedPreferences("home-widgets", 0).getString("encrypted", "");
        assertFalse(disk.contains("Parcel ready")); assertFalse(disk.contains("Team meeting"));
        new HomeWidgetStore(context).clear(); assertEquals(0, new HomeWidgetStore(context).read().length());
    }

    @Test public void filtersEndedEventsAcrossTheDstBoundaryWithoutMovingAllDayDates() throws Exception {
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("eventId", "past").put("endTime", "2026-10-24T23:00:00Z"))
            .put(new JSONObject().put("eventId", "holiday").put("allDay", true).put("startTime", "2026-10-25")
                .put("endTime", "2026-10-26").put("timezone", "Europe/Berlin"))
            .put(new JSONObject().put("eventId", "invalid").put("endTime", "bad"));
        JSONObject cache = new JSONObject().put("calendar", new JSONObject().put("items", items));
        assertEquals(1, HomeWidgetViews.items(cache, "calendar", Instant.parse("2026-10-25T23:00:00Z").minusSeconds(1)).length());
        assertEquals(0, HomeWidgetViews.items(cache, "calendar", Instant.parse("2026-10-25T23:00:00Z")).length());
    }

    @Test public void rendersTranslucentPanelsAndRetainsReadableOpaqueQrPixels() throws Exception {
        instrumentation.runOnMainSync(() -> {
            View widget = HomeWidgetViews.create(context, "mail", 99, new JSONObject()).apply(context, new FrameLayout(context));
            Bitmap bitmap = Bitmap.createBitmap(400, 250, Bitmap.Config.ARGB_8888);
            widget.getBackground().setBounds(0, 0, 400, 250); widget.getBackground().draw(new Canvas(bitmap));
            int alpha = Color.alpha(bitmap.getPixel(200, 120));
            assertTrue("Wallpaper must show through the panel", alpha > 0 && alpha < 100);
        });
        Bitmap qr = LoyaltyQrRenderer.render("fixture-pass", 256);
        assertEquals(255, Color.alpha(qr.getPixel(0, 0)));
        assertEquals(Color.WHITE, qr.getPixel(0, 0));
    }

    @Test public void realWidgetHostLoadsScrollingMailAndAgendaAndKeepsTheThreeQrTilesOnOneRow() throws Exception {
        boolean airplaneWasOn = shell("settings get global airplane_mode_on").trim().equals("1");
        AppWidgetHost host = new AppWidgetHost(context, WidgetPreviewActivity.HOST_ID);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = new int[3];
        Class<?>[] providers = {MailWidgetProvider.class, CalendarWidgetProvider.class, LoyaltyWidgetProvider.class};
        Activity activity = null;
        try {
            shell("cmd connectivity airplane-mode enable");
            android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
            long offlineDeadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (connectivity.getActiveNetwork() != null && android.os.SystemClock.elapsedRealtime() < offlineDeadline) Thread.sleep(100);
            assertNull("The cache fixture must run offline, without a signed-out background refresh", connectivity.getActiveNetwork());
            host.deleteHost(); // Reconcile only this fixture's host after an interrupted earlier run.
            instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
            for (int i = 0; i < 3; i++) {
                ids[i] = host.allocateAppWidgetId();
                assertTrue("Widget host permission", manager.bindAppWidgetIdIfAllowed(ids[i], new ComponentName(context, providers[i])));
            }
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
            instrumentation.waitForIdleSync(); HomeWidgetJob.cancel(context);
            long idle = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (HomeWidgetSync.isRunning() && android.os.SystemClock.elapsedRealtime() < idle) Thread.sleep(100);
            assertFalse("Initial signed-out refresh should finish", HomeWidgetSync.isRunning());
            new HomeWidgetStore(context).save(fixture());
            Map<String, String> codes = new HashMap<>(); codes.put("rewe", "R123456789"); codes.put("lidl-plus", "L123456789"); codes.put("action", "A123456789");
            new LoyaltyCodeStore(context).replace(codes);
            HomeWidgetProvider.updateAll(context); LoyaltyWidgetProvider.updateAll(context);
            Intent intent = new Intent(context, WidgetPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("widgetIds", ids);
            activity = instrumentation.startActivitySync(intent);
            final Activity shown = activity;
            long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            boolean[] ready = {false};
            while (!ready[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> ready[0] = contains(shown.getWindow().getDecorView(), "Parcel ready") && contains(shown.getWindow().getDecorView(), "Team meeting"));
                if (!ready[0]) Thread.sleep(100);
            }
            File output = new File(context.getExternalFilesDir(null), ready[0] ? "home-widgets-preview.png" : "home-widgets-preview-failed.png");
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(screenshot);
            try (FileOutputStream out = new FileOutputStream(output)) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)); }
            JSONObject retained = new HomeWidgetStore(context).read();
            assertTrue("Actual collection adapters should render cached mail and agenda; retained mail="
                + HomeWidgetViews.items(retained, "mail").length() + ", agenda=" + HomeWidgetViews.items(retained, "calendar").length(), ready[0]);
            instrumentation.runOnMainSync(() -> {
                // AppWidgetHostView is a root namespace, so walk its children explicitly.
                View root = shown.getWindow().getDecorView();
                View rewe = findId(root, R.id.loyalty_tile_rewe), action = findId(root, R.id.loyalty_tile_action), lidl = findId(root, R.id.loyalty_tile_lidl);
                assertNotNull(rewe); assertSame(rewe.getParent(), lidl.getParent()); assertSame(rewe.getParent(), action.getParent());
            });
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(WidgetDetailActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(() -> {
                    View row = findText(shown.getWindow().getDecorView(), "Team meeting");
                    assertNotNull(row);
                    while (!(row.getParent() instanceof android.widget.ListView)) row = (View) row.getParent();
                    android.widget.ListView list = (android.widget.ListView) row.getParent();
                    list.performItemClick(row, 0, list.getItemIdAtPosition(0));
                });
                Activity detail = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull("A real widget row should open native event details", detail);
                assertEquals("fixture-event", detail.getIntent().getStringExtra("itemId"));
                assertFalse(detail.getIntent().hasExtra("description"));
                instrumentation.runOnMainSync(() -> {
                    assertTrue(contains(detail.getWindow().getDecorView(), "Team meeting"));
                    detail.finish();
                });
            } finally { instrumentation.removeMonitor(monitor); }
        } finally {
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
            if (activity != null) { Activity done = activity; instrumentation.runOnMainSync(done::finish); }
            host.deleteHost(); HomeWidgetJob.cancel(context);
            if (!airplaneWasOn) shell("cmd connectivity airplane-mode disable");
        }
    }

    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor file = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.FileInputStream in = new java.io.FileInputStream(file.getFileDescriptor());
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private View findId(View view, int id) {
        if (view.getId() == id) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findId(((ViewGroup) view).getChildAt(i), id); if (found != null) return found;
        }
        return null;
    }
    private View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findText(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private JSONObject fixture() throws Exception {
        JSONObject mail = new JSONObject().put("aquaMail", true).put("items", new JSONArray().put(new JSONObject().put("messageId", "fixture-mail-1")
            .put("viewUri", "content://org.kman.AquaMail.datax.messages/fixture/1")
            .put("subject", "Parcel ready").put("date", Instant.now().toString()).put("snippet", "Your parcel is ready for collection.")
            .put("from", new JSONArray().put(new JSONObject().put("name", "Delivery").put("address", "delivery@example.test"))))
            .put(new JSONObject().put("messageId", "fixture-mail-2").put("subject", "Your booking")
                .put("viewUri", "content://org.kman.AquaMail.datax.messages/fixture/2")
                .put("date", Instant.now().toString()).put("snippet", "Your reservation is confirmed.")
                .put("from", new JSONArray().put(new JSONObject().put("name", "Travel").put("address", "travel@example.test")))))
            .put("updatedAt", System.currentTimeMillis());
        JSONObject event = new JSONObject().put("eventId", "fixture-event").put("title", "Team meeting")
            .put("startTime", Instant.now().plusSeconds(3600).toString()).put("endTime", Instant.now().plusSeconds(7200).toString())
            .put("timezone", "Europe/Berlin").put("location", "Office");
        JSONObject holiday = new JSONObject().put("eventId", "fixture-holiday").put("title", "Holiday").put("allDay", true)
            .put("startTime", LocalDate.now().plusDays(1).toString()).put("endTime", LocalDate.now().plusDays(2).toString()).put("timezone", "Europe/Berlin");
        return new JSONObject().put("signedOut", false).put("mail", mail).put("calendar", new JSONObject()
            .put("items", new JSONArray().put(event).put(holiday)).put("updatedAt", System.currentTimeMillis()));
    }
}
