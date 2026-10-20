package page.claras.pandemonium;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public abstract class HomeWidgetProvider extends AppWidgetProvider {
    static final String REFRESH = "page.claras.pandemonium.REFRESH_HOME_WIDGETS";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
        HomeWidgetJob.schedule(context, true);
    }
    @Override public void onEnabled(Context context) { HomeWidgetJob.schedule(context, true); }
    @Override public void onDisabled(Context context) {
        if (!any(context)) HomeWidgetJob.cancel(context);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle options) {
        updateAll(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) HomeWidgetJob.schedule(context, true);
    }

    static boolean any(Context context) { return ids(context, MailWidgetProvider.class).length + ids(context, CalendarWidgetProvider.class).length > 0; }
    static int[] ids(Context context, Class<?> provider) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, provider));
    }
    static void updateAll(Context context) {
        JSONObject cache = new HomeWidgetStore(context).read();
        update(context, MailWidgetProvider.class, HomeWidgetViews.MAIL, cache);
        update(context, CalendarWidgetProvider.class, HomeWidgetViews.CALENDAR, cache);
    }
    private static void update(Context context, Class<?> provider, String kind, JSONObject cache) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = ids(context, provider);
        for (int id : ids) manager.updateAppWidget(id, HomeWidgetViews.create(context, kind, id, cache));
        if (ids.length > 0) manager.notifyAppWidgetViewDataChanged(ids, R.id.home_widget_list);
    }
}
