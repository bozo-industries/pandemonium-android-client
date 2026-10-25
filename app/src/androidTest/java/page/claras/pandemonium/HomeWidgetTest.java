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
        assertFalse(disk.contains("Parcel ready")); assertFalse(disk.contains("Late Shift"));
        new HomeWidgetStore(context).clear(); assertEquals(0, new HomeWidgetStore(context).read().length());
    }

    @Test public void rendersCompletedWorkShiftsUntilBerlinMondayAndRejectsLegacyAgenda() throws Exception {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        JSONObject response = WorkWeekSourceTest.week(now);
        WorkWeekSourceTest.add(response, 0, WorkWeekSourceTest.shift("monday", "2026-10-19T06:00:00Z", "2026-10-19T14:00:00Z"));
        JSONObject cache = new JSONObject().put("calendar", WorkWeekSource.read(response, now));
        assertEquals(1, HomeWidgetViews.items(cache, "calendar", Instant.parse("2026-10-25T22:59:59Z")).length());
        assertEquals(0, HomeWidgetViews.items(cache, "calendar", Instant.parse("2026-10-25T23:00:00Z")).length());
        cache.put("calendar", new JSONObject().put("items", new JSONArray().put(new JSONObject().put("eventId", "personal"))));
        assertEquals(0, HomeWidgetViews.items(cache, "calendar", now).length());
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
                instrumentation.runOnMainSync(() -> ready[0] = contains(shown.getWindow().getDecorView(), "Parcel ready") && contains(shown.getWindow().getDecorView(), "Late Shift"));
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
                View calendarHost = findWidgetHost(root, ids[1]); assertNotNull(calendarHost);
                android.widget.ListView list = (android.widget.ListView) findId(calendarHost, R.id.home_widget_list);
                assertEquals("All planned shifts must reach the actual collection adapter", 150, list.getAdapter().getCount());
                list.setSelection(149);
            });
            boolean[] lastVisible = {false};
            long scrollDeadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (!lastVisible[0] && android.os.SystemClock.elapsedRealtime() < scrollDeadline) {
                instrumentation.runOnMainSync(() -> lastVisible[0] = contains(shown.getWindow().getDecorView(), "Work shift 150"));
                if (!lastVisible[0]) Thread.sleep(100);
            }
            assertTrue("The scrolling host must reach the final shift beyond the old 100-row cap", lastVisible[0]);
            instrumentation.runOnMainSync(() -> ((android.widget.ListView) findId(findWidgetHost(shown.getWindow().getDecorView(), ids[1]), R.id.home_widget_list)).setSelection(0));
            boolean[] firstVisible = {false};
            long restoreDeadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (!firstVisible[0] && android.os.SystemClock.elapsedRealtime() < restoreDeadline) {
                instrumentation.runOnMainSync(() -> firstVisible[0] = contains(shown.getWindow().getDecorView(), "Late Shift"));
                if (!firstVisible[0]) Thread.sleep(100);
            }
            assertTrue(firstVisible[0]);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(() -> {
                    View row = findText(shown.getWindow().getDecorView(), "Late Shift");
                    assertNotNull(row);
                    while (!(row.getParent() instanceof android.widget.ListView)) row = (View) row.getParent();
                    android.widget.ListView list = (android.widget.ListView) row.getParent();
                    list.performItemClick(row, 0, list.getItemIdAtPosition(0));
                });
                Activity detail = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull("A real widget row should open the Pandemonium app", detail);
                assertEquals("fixture-event", detail.getIntent().getStringExtra("itemId"));
                assertFalse(detail.getIntent().hasExtra("description"));
                WorkNavigationTest.assertRequestedUrl(instrumentation, detail, "https://claras.page/work/");
                instrumentation.runOnMainSync(detail::onBackPressed);
                assertTrue("Back from a cold Work page closes the shell", detail.isFinishing());
                monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
                instrumentation.runOnMainSync(() -> findId(findWidgetHost(shown.getWindow().getDecorView(), ids[1]), R.id.home_widget_title).performClick());
                Activity header = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull("The real Work header should open the app", header);
                WorkNavigationTest.assertRequestedUrl(instrumentation, header, "https://claras.page/work/");
                instrumentation.runOnMainSync(header::finish);
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
    private View findWidgetHost(View view, int id) {
        if (view instanceof android.appwidget.AppWidgetHostView
                && ((android.appwidget.AppWidgetHostView) view).getAppWidgetId() == id) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findWidgetHost(((ViewGroup) view).getChildAt(i), id); if (found != null) return found;
        }
        return null;
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
        Instant now = Instant.now();
        JSONObject week = WorkWeekSourceTest.week(now);
        Instant monday = WorkWeekSource.weekStart(now).atTime(8, 0).atZone(WorkWeekSource.ZONE).toInstant();
        for (int i = 0; i < 150; i++) {
            Instant starts = monday.plusSeconds(i * 60);
            JSONObject shift = WorkWeekSourceTest.shift(i == 0 ? "fixture-event" : "fixture-work-" + i,
                starts.toString(), starts.plusSeconds(60).toString()).put("title", i == 0 ? "Late Shift" : "Work shift " + (i + 1));
            WorkWeekSourceTest.add(week, 0, shift);
        }
        return new JSONObject().put("signedOut", false).put("mail", mail).put("calendar", WorkWeekSource.read(week, now));
    }
}
