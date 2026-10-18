package page.claras.pandemonium;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoyaltyWidgetTest {
    private Context context;
    private LoyaltyCodeStore store;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new LoyaltyCodeStore(context);
        store.clear();
    }

    @After public void tearDown() throws Exception {
        store.clear();
    }

    @Test public void cachesOnlyStaticActionReweAndLidlPasses() throws Exception {
        Map<String, String> codes = new HashMap<>();
        assertTrue(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.ACTION, card("action", "ready", "A123", "A123", null), codes));
        assertTrue(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.REWE, card("rewe", "ready", "R123", "R123", null), codes));
        assertTrue(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.LIDL, card("lidl-plus", "ready", "L123", "L1231", null), codes));
        assertFalse(LoyaltyWalletClient.mergeCard("mcdonalds", card("mcdonalds", "ready", "dynamic", "dynamic", null), codes));
        assertEquals("A123", codes.get(LoyaltyCodeStore.ACTION));
        assertEquals("R123", codes.get(LoyaltyCodeStore.REWE));
        assertEquals("L1231", codes.get(LoyaltyCodeStore.LIDL));
        assertFalse(codes.containsKey("mcdonalds"));

        assertFalse(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.LIDL, card("lidl-plus", "ready", "L123", "temporary", null), codes));
        assertTrue(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.REWE, card("rewe", "ready", "R123", "R123", "2030-01-01T00:00:00Z"), codes));
        assertFalse(codes.containsKey(LoyaltyCodeStore.REWE));
        assertTrue(LoyaltyWalletClient.mergeCard(LoyaltyCodeStore.ACTION, card("action", "missing", null, null, null), codes));
        assertFalse(codes.containsKey(LoyaltyCodeStore.ACTION));
    }

    @Test public void encryptsAndRestoresLoyaltyCodes() throws Exception {
        String privateCode = "L2763658450662";
        store.replace(Collections.singletonMap(LoyaltyCodeStore.ACTION, privateCode));
        assertEquals(privateCode, new LoyaltyCodeStore(context).read().get(LoyaltyCodeStore.ACTION));
        assertFalse(context.getSharedPreferences("loyalty-widget", Context.MODE_PRIVATE)
            .getString("encrypted", "").contains(privateCode));
    }

    @Test public void encodesAndDisplaysAFullSizeScannableQrWithoutPuttingTheCodeInTheIntent() throws Exception {
        String payload = "L2763658450662";
        store.replace(Collections.singletonMap(LoyaltyCodeStore.ACTION, payload));
        Bitmap bitmap = LoyaltyQrRenderer.render(payload, 384);
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        Result decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
            new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels))));
        assertEquals(payload, decoded.getText());

        Intent intent = new Intent(context, LoyaltyQrActivity.class)
            .putExtra(LoyaltyQrActivity.EXTRA_PROVIDER, LoyaltyCodeStore.ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        try {
            ImageView image = activity.findViewById(R.id.loyalty_qr_fullscreen);
            assertNotNull(image);
            assertNotNull(image.getDrawable());
            assertTrue(image.getLayoutParams().width >= Math.round(220 * context.getResources().getDisplayMetrics().density));
            assertFalse(activity.getIntent().hasExtra("qr_payload"));
            assertFalse(activity.getIntent().toUri(0).contains(payload));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    @Test public void homeWidgetTileShowsThePassAndOpensItsLargeQr() throws Exception {
        String payload = "R123456789";
        Map<String, String> passes = new HashMap<>();
        passes.put(LoyaltyCodeStore.REWE, payload);
        passes.put(LoyaltyCodeStore.LIDL, "L123456789");
        passes.put(LoyaltyCodeStore.ACTION, "A123456789");
        store.replace(passes);
        RemoteViews remoteViews = LoyaltyWidgetViews.create(context, passes);
        View widget = remoteViews.apply(context, new FrameLayout(context));
        View tile = widget.findViewById(R.id.loyalty_tile_rewe);
        View lidlTile = widget.findViewById(R.id.loyalty_tile_lidl);
        View actionTile = widget.findViewById(R.id.loyalty_tile_action);
        assertNotNull(tile);
        assertNotNull(lidlTile);
        assertNotNull(actionTile);
        assertSame("All loyalty tiles must occupy the same horizontal row", tile.getParent(), lidlTile.getParent());
        assertSame("Action must share the row with REWE and Lidl", tile.getParent(), actionTile.getParent());
        assertEquals(tile.getTop(), lidlTile.getTop());
        assertEquals(tile.getTop(), actionTile.getTop());
        assertEquals(View.VISIBLE, widget.findViewById(R.id.loyalty_qr_rewe).getVisibility());
        assertEquals(View.VISIBLE, widget.findViewById(R.id.loyalty_qr_lidl).getVisibility());
        assertEquals(View.VISIBLE, widget.findViewById(R.id.loyalty_qr_action).getVisibility());
        assertEquals(View.GONE, widget.findViewById(R.id.loyalty_missing_rewe).getVisibility());

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(LoyaltyQrActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(tile::performClick);
            Activity activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000);
            assertNotNull("Tapping the widget tile should open its QR activity", activity);
            assertEquals(LoyaltyCodeStore.REWE,
                activity.getIntent().getStringExtra(LoyaltyQrActivity.EXTRA_PROVIDER));
            assertNotNull(activity.findViewById(R.id.loyalty_qr_fullscreen));
            instrumentation.runOnMainSync(activity::finish);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private static JSONObject card(String providerId, String state, String displayCode, String qrContent, String expiresAt) throws Exception {
        JSONObject result = new JSONObject().put("providerId", providerId).put("state", state);
        if (displayCode != null) result.put("displayCode", displayCode);
        if (qrContent != null) result.put("qrContent", qrContent);
        if (expiresAt != null) result.put("expiresAt", expiresAt);
        return result;
    }
}
