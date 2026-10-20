package page.claras.pandemonium;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.drawable.GradientDrawable;

/** Debug-only real AppWidgetHost; never packaged into a release. */
public final class WidgetPreviewActivity extends Activity {
    static final int HOST_ID = 44217;
    private AppWidgetHost host;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        host = new AppWidgetHost(this, HOST_ID);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF384958, 0xFF8A7774, 0xFF464150}));
        LinearLayout stack = new LinearLayout(this); stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(10), dp(16), dp(10), dp(20));
        scroll.addView(stack); setContentView(scroll);
        int[] ids = getIntent().getIntArrayExtra("widgetIds");
        if (ids == null || ids.length != 3) { finish(); return; }
        int[] heights = {230, 230, 160};
        for (int i = 0; i < ids.length; i++) {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(ids[i]);
            if (info == null) { finish(); return; }
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(heights[i]));
            layout.bottomMargin = dp(12);
            stack.addView(host.createView(this, ids[i], info), layout);
        }
        host.startListening();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onDestroy() { if (host != null) host.stopListening(); super.onDestroy(); }
}
