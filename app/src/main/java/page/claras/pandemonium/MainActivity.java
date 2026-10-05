package page.claras.pandemonium;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.pm.PackageManager;
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
import android.webkit.PermissionRequest;
import android.provider.MediaStore;
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
    private PermissionRequest cameraRequest;
    private Uri captureUri;
    private ValueCallback<Uri[]> cameraFileRequest;
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
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                cancelCameraRequest();
                finishFile(null);
            }
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
            @Override public void onPermissionRequest(PermissionRequest request) {
                cancelCameraRequest();
                if (!cameraAllowed(request.getOrigin(), request.getResources()) || !trustedPage()) { request.deny(); return; }
                cameraRequest = request;
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) finishCameraRequest();
                else requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (cameraRequest == request) cameraRequest = null;
            }
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                finishFile(null);
                fileCallback = callback;
                if (!trustedPage()) { finishFile(null); return true; }
                boolean image = params.getAcceptTypes().length == 0 || java.util.Arrays.stream(params.getAcceptTypes())
                    .flatMap(type -> java.util.Arrays.stream(type.split(",")))
                    .map(String::trim).anyMatch(type -> type.isEmpty() || type.equals("*/*") || type.startsWith("image/"));
                if (image && params.isCaptureEnabled()) takePhoto();
                else if (image) new AlertDialog.Builder(MainActivity.this)
                    .setItems(new String[]{getString(R.string.take_photo), getString(R.string.choose_file)}, (dialog, choice) -> {
                        if (choice == 0) takePhoto(); else chooseFile(params);
                    }).setOnCancelListener(dialog -> finishFile(null)).show();
                else chooseFile(params);
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

    static boolean cameraAllowed(Uri origin, String[] resources) {
        return internal(origin) && java.util.Arrays.asList(resources).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
    }

    private boolean trustedPage() { return web.getUrl() != null && internal(Uri.parse(web.getUrl())); }
    private void cancelCameraRequest() {
        if (cameraRequest != null) { cameraRequest.deny(); cameraRequest = null; }
    }
    private void finishCameraRequest() {
        PermissionRequest request = cameraRequest;
        cameraRequest = null;
        if (request == null) return;
        if (trustedPage() && cameraAllowed(request.getOrigin(), request.getResources())
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else request.deny();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 2) finishCameraRequest();
        if (code == 3) {
            boolean current = cameraFileRequest != null && cameraFileRequest == fileCallback;
            cameraFileRequest = null;
            if (current && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && trustedPage()) takePhoto();
            else if (current) finishFile(null);
        }
    }
    private void chooseFile(WebChromeClient.FileChooserParams params) {
        try { startActivityForResult(params.createIntent(), 1); }
        catch (ActivityNotFoundException error) { finishFile(null); }
    }
    private void takePhoto() {
        if (fileCallback == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraFileRequest = fileCallback;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
            return;
        }
        try {
            captureUri = CaptureProvider.create(this);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            intent.setClipData(ClipData.newRawUri("Photo", captureUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, 1);
        } catch (java.io.IOException | ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show();
            finishFile(null);
        }
    }
    private void finishFile(Uri[] result) {
        cameraFileRequest = null;
        if (captureUri != null) {
            revokeUriPermission(captureUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (result == null) try { CaptureProvider.file(this, captureUri).delete(); } catch (java.io.IOException ignored) { }
            captureUri = null;
        }
        if (fileCallback != null) { fileCallback.onReceiveValue(result); fileCallback = null; }
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
            Uri[] files = WebChromeClient.FileChooserParams.parseResult(result, data);
            if (result == RESULT_OK && captureUri != null) {
                try { if (CaptureProvider.file(this, captureUri).length() > 0) files = new Uri[]{captureUri}; }
                catch (java.io.IOException ignored) { }
            }
            finishFile(files);
        }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(sessionTick);
        cancelCameraRequest();
        finishFile(null);
        web.destroy();
        super.onDestroy();
    }
}
