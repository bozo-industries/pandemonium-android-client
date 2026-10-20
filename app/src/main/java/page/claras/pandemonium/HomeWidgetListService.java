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
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() {
                items = HomeWidgetViews.items(new HomeWidgetStore(getApplicationContext()).read(), kind);
            }
            @Override public void onDestroy() { items = new JSONArray(); }
            @Override public int getCount() { return Math.min(100, items.length()); }
            @Override public RemoteViews getViewAt(int position) {
                JSONObject item = items.optJSONObject(position);
                return item == null ? null : HomeWidgetViews.row(getApplicationContext(), kind, item);
            }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return false; }
        };
    }
}
