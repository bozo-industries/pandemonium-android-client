package page.claras.pandemonium;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

/** A single-origin browser shell. Authentication stays in the website's HttpOnly cookies. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://claras.page/ui";
    private WebView web;
    private Button retry;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean loaded;
    private final Runnable sessionTick = new Runnable() {
        @Override public void run() {
            syncSession();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(21, 16, 29));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 6));
        retry = new Button(this);
        retry.setText(R.string.connection_error);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(view -> {
            retry.setVisibility(View.GONE);
            SessionManager.get(this).sync(result -> {
                if (isDestroyed()) return;
                web.reload();
                if (result == SessionManager.Result.UNAVAILABLE) showSessionError();
            });
        });
        root.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (internal(uri)) return false;
                if (request.isForMainFrame()) openExternal(uri);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                Uri page = Uri.parse(url);
                if (internal(page) && page.getPath() != null) getPreferences(MODE_PRIVATE).edit().putString("lastPath", page.getPath()).apply();
                syncSession();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { retry.setText(R.string.connection_error); retry.setVisibility(View.VISIBLE); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), 1); }
                catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::navigateBack);
        }
        SessionManager.get(this).sync(result -> {
            if (isDestroyed()) return;
            String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN);
            String lastPath = getPreferences(MODE_PRIVATE).getString("lastPath", "/ui");
            String initialUrl = SessionManager.cookieValue(cookie, SessionManager.SESSION) != null && lastPath.startsWith("/") && !lastPath.startsWith("//")
                ? SessionManager.ORIGIN + lastPath : HOME;
            if (state == null || web.restoreState(state) == null) web.loadUrl(initialUrl);
            loaded = true;
            if (result == SessionManager.Result.UNAVAILABLE) showSessionError();
        });
    }

    static boolean internal(Uri uri) {
        return "https".equals(uri.getScheme()) && "claras.page".equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme) && !"tel".equals(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.no_link_handler, Toast.LENGTH_SHORT).show(); }
    }

    private void navigateBack() { if (web.canGoBack()) web.goBack(); else finish(); }
    private void syncSession() {
        SessionManager.get(this).sync(result -> {
            if (isDestroyed()) return;
            if (result == SessionManager.Result.RELOAD && loaded) web.reload();
            if (result == SessionManager.Result.UNAVAILABLE) showSessionError();
        });
    }
    private void showSessionError() {
        retry.setText(R.string.session_error);
        retry.setVisibility(View.VISIBLE);
    }
    @Override public void onBackPressed() { navigateBack(); }
    @Override protected void onPause() { handler.removeCallbacks(sessionTick); CookieManager.getInstance().flush(); syncSession(); web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); handler.post(sessionTick); }
    @Override protected void onSaveInstanceState(Bundle state) { web.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(sessionTick);
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        web.destroy();
        super.onDestroy();
    }
}
