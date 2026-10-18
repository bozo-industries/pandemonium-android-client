package page.claras.pandemonium;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class LoyaltyQrActivity extends Activity {
    static final String EXTRA_PROVIDER = "loyalty_provider";
    private final Handler main = new Handler(Looper.getMainLooper());
    private String provider;
    private ImageView codeView;
    private TextView statusView;
    private Button openApp;
    private boolean refreshStarted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        provider = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (!LoyaltyCodeStore.supports(provider)) {
            finish();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(21, 16, 29));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        content.setPadding(pad, dp(32), pad, dp(24));

        TextView title = new TextView(this);
        title.setText(LoyaltyCodeStore.label(provider));
        title.setTextColor(Color.rgb(244, 239, 250));
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        codeView = new ImageView(this);
        codeView.setId(R.id.loyalty_qr_fullscreen);
        codeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        codeView.setContentDescription(getString(R.string.loyalty_qr_content_description, LoyaltyCodeStore.label(provider)));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, -2);
        imageParams.width = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(48));
        imageParams.height = imageParams.width;
        imageParams.topMargin = dp(20);
        content.addView(codeView, imageParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(207, 195, 223));
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(18), 0, dp(8));
        content.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        openApp = new Button(this);
        openApp.setText(R.string.loyalty_qr_open_app);
        openApp.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        content.addView(openApp, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content);
        setContentView(scroll);
        renderSavedCode();
    }

    @Override protected void onResume() {
        super.onResume();
        if (provider == null) return;
        renderSavedCode();
        if (codeView.getDrawable() == null && !refreshStarted) {
            refreshStarted = true;
            statusView.setText(R.string.loyalty_qr_loading);
            LoyaltyCodeSync.refresh(this, true, () -> main.post(this::renderSavedCode));
        }
    }

    private void renderSavedCode() {
        if (codeView == null || provider == null) return;
        try {
            String payload = new LoyaltyCodeStore(this).read().get(provider);
            if (payload != null) {
                int size = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(48));
                Bitmap bitmap = LoyaltyQrRenderer.render(payload, size);
                codeView.setImageBitmap(bitmap);
                statusView.setText("");
                openApp.setVisibility(View.GONE);
                return;
            }
        } catch (Exception ignored) { }
        codeView.setImageDrawable(null);
        statusView.setText(R.string.loyalty_qr_unavailable);
        openApp.setVisibility(View.VISIBLE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
