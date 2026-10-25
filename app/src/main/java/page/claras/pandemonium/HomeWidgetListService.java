package page.claras.pandemonium;

import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HomeWidgetListService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        String value = intent.getStringExtra("kind");
        final String kind = HomeWidgetViews.valid(value) ? value : HomeWidgetViews.MAIL;
        return new RemoteViewsFactory() {
            private JSONArray items = new JSONArray();
            private MailWidgetStore.Reader mail;
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public synchronized void onDataSetChanged() {
                if (mail != null) { mail.close(); mail = null; }
                if (HomeWidgetViews.MAIL.equals(kind)) {
                    try { mail = new MailWidgetStore(getApplicationContext()).reader(java.time.Instant.now()); }
                    catch (Exception error) {
                        try { new MailWidgetStore(getApplicationContext()).markFailure("cache_error"); } catch (Exception ignored) { }
                    }
                    return;
                }
                items = HomeWidgetViews.items(new HomeWidgetStore(getApplicationContext()).read(), kind);
            }
            @Override public synchronized void onDestroy() { items = new JSONArray(); if (mail != null) { mail.close(); mail = null; } }
            @Override public synchronized int getCount() { return HomeWidgetViews.MAIL.equals(kind) ? mail == null ? 0 : mail.count() : items.length(); }
            @Override public synchronized RemoteViews getViewAt(int position) {
                JSONObject item;
                try { item = HomeWidgetViews.MAIL.equals(kind) ? mail == null ? null : mail.row(position) : items.optJSONObject(position); }
                catch (Exception error) {
                    try { new MailWidgetStore(getApplicationContext()).markFailure("cache_error"); } catch (Exception ignored) { }
                    RemoteViews unavailable = new RemoteViews(getPackageName(), R.layout.widget_mail_row);
                    unavailable.setTextViewText(R.id.home_widget_row_title, getString(R.string.mail_widget_cache_error));
                    unavailable.setOnClickFillInIntent(R.id.home_widget_row, new Intent().putExtra("itemId", "unavailable"));
                    unavailable.setContentDescription(R.id.home_widget_row, getString(R.string.mail_widget_cache_error));
                    return unavailable;
                }
                return item == null ? null : HomeWidgetViews.row(getApplicationContext(), kind, item);
            }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return false; }
        };
    }
}
