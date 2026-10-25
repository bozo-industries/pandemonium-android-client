package page.claras.pandemonium;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, read-only agenda details. Older mail widget intents are handed to Aqua Mail. */
public final class WidgetDetailActivity extends Activity {
    private LinearLayout content;
    private String kind, selectedId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        kind = getIntent().getStringExtra("kind");
        if (!HomeWidgetViews.valid(kind)) { finish(); return; }
        selectedId = state == null ? getIntent().getStringExtra("itemId") : state.getString("itemId");
        if (HomeWidgetViews.MAIL.equals(kind)) {
            startActivity(new Intent(this, MailClientActivity.class).putExtra("itemId", selectedId));
            finish();
            return;
        }
        getWindow().setStatusBarColor(Color.rgb(16, 13, 24));
        getWindow().setNavigationBarColor(Color.rgb(16, 13, 24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 13, 24));
        root.setPadding(0, 0, 0, 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText(R.string.widget_back);
        back.setOnClickListener(view -> { if (selectedId == null) finish(); else { selectedId = null; show(); } });
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(76), dp(52)));
        TextView title = text(getString(R.string.work_widget_title), 20);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button refresh = new Button(this); refresh.setText(R.string.widget_refresh);
        refresh.setOnClickListener(view -> refresh());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(96), dp(52)));
        root.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        show();
    }

    private void show() {
        content.removeAllViews();
        if (selectedId != null) { showSelected(); return; }
        JSONObject cache = new HomeWidgetStore(this).read();
        JSONArray items = HomeWidgetViews.items(cache, kind);
        if (items.length() == 0) {
            content.addView(text(getString(cache.optBoolean("signedOut", !cache.has(kind)) ? R.string.widget_sign_in
                : WorkWeekSource.isCurrent(cache.optJSONObject(kind), java.time.Instant.now())
                    ? R.string.widget_no_events : R.string.widget_work_unavailable), 16));
            Button open = new Button(this); open.setText(R.string.widget_open_app);
            open.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
            content.addView(open);
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(12), 0, dp(12));
            row.addView(text(HomeWidgetViews.eventDay(item) + " · "
                + (item.optBoolean("allDay") ? getString(R.string.widget_all_day) : HomeWidgetViews.eventTime(item)), 13));
            row.addView(text(item.optString("title"), 18));
            String preview = item.optString("location");
            if (!preview.isEmpty()) row.addView(text(preview, 14));
            row.setOnClickListener(view -> { selectedId = item.optString("eventId"); show(); });
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            content.addView(row);
            View divider = new View(this); divider.setBackgroundColor(0x24FFFFFF); content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void showSelected() {
        JSONArray items = HomeWidgetViews.items(new HomeWidgetStore(this).read(), kind);
        JSONObject selected = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && selectedId.equals(item.optString("eventId"))) { selected = item; break; }
        }
        if (selected == null) { content.addView(text(getString(R.string.widget_missing_item), 16)); return; }
        content.addView(text(selected.optString("title"), 25));
        content.addView(text(HomeWidgetViews.eventDay(selected) + " · " + (selected.optBoolean("allDay")
            ? getString(R.string.widget_all_day) : HomeWidgetViews.eventTime(selected)), 16));
        if (!selected.optString("location").isEmpty()) content.addView(text(selected.optString("location"), 16));
        content.addView(text(selected.optString("description"), 16));
    }

    private void refresh() {
        content.removeAllViews(); content.addView(text(getString(R.string.widget_loading), 16));
        HomeWidgetSync.refresh(this, true, () -> { if (!isDestroyed()) show(); });
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(0xFFF7F3FA); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onSaveInstanceState(Bundle out) { out.putString("itemId", selectedId); super.onSaveInstanceState(out); }
}
