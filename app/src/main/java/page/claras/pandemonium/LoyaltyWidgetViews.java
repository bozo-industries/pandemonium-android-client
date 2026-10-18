package page.claras.pandemonium;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Map;

final class LoyaltyWidgetViews {
    private LoyaltyWidgetViews() { }

    static RemoteViews create(Context context, Map<String, String> payloads) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_loyalty_codes);
        bind(context, views, payloads, LoyaltyCodeStore.REWE,
            R.id.loyalty_tile_rewe, R.id.loyalty_qr_rewe, R.id.loyalty_missing_rewe);
        bind(context, views, payloads, LoyaltyCodeStore.LIDL,
            R.id.loyalty_tile_lidl, R.id.loyalty_qr_lidl, R.id.loyalty_missing_lidl);
        bind(context, views, payloads, LoyaltyCodeStore.ACTION,
            R.id.loyalty_tile_action, R.id.loyalty_qr_action, R.id.loyalty_missing_action);
        return views;
    }

    private static void bind(Context context, RemoteViews views, Map<String, String> payloads,
                             String provider, int tileId, int imageId, int missingId) {
        String payload = payloads.get(provider);
        boolean rendered = false;
        if (payload != null) {
            try {
                int size = Math.max(128, Math.round(68f * context.getResources().getDisplayMetrics().density));
                views.setImageViewBitmap(imageId, LoyaltyQrRenderer.render(payload, size));
                rendered = true;
            } catch (Exception ignored) { }
        }
        views.setViewVisibility(imageId, rendered ? View.VISIBLE : View.GONE);
        views.setViewVisibility(missingId, rendered ? View.GONE : View.VISIBLE);
        views.setContentDescription(tileId, LoyaltyCodeStore.label(provider) + " QR code. Tap to enlarge.");
        Intent intent = new Intent(context, LoyaltyQrActivity.class)
            .setAction("page.claras.pandemonium.OPEN_LOYALTY_QR")
            .setData(Uri.parse("pandemonium://loyalty/" + provider))
            .putExtra(LoyaltyQrActivity.EXTRA_PROVIDER, provider);
        PendingIntent pending = PendingIntent.getActivity(context, provider.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(tileId, pending);
    }
}
