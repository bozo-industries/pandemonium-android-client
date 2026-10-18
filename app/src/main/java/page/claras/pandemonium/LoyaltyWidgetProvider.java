package page.claras.pandemonium;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.widget.RemoteViews;
import java.util.Collections;
import java.util.Map;

public final class LoyaltyWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        updateAll(context);
        if (widgetIds == null || widgetIds.length == 0) return;
        PendingResult pending = goAsync();
        LoyaltyCodeSync.refresh(context, true, pending::finish);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, android.os.Bundle options) {
        updateAll(context);
    }

    static void updateAll(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] ids = manager.getAppWidgetIds(new ComponentName(app, LoyaltyWidgetProvider.class));
        if (ids.length == 0) return;
        Map<String, String> payloads;
        try { payloads = new LoyaltyCodeStore(app).read(); }
        catch (Exception ignored) { payloads = Collections.emptyMap(); }
        RemoteViews views = LoyaltyWidgetViews.create(app, payloads);
        for (int id : ids) manager.updateAppWidget(id, views);
    }
}
