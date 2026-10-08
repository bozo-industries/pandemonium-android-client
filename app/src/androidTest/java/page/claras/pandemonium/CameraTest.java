package page.claras.pandemonium;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CameraTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void limitsWebCameraPermissionToTheTrustedOriginAndVideoResource() {
        assertTrue(MainActivity.cameraAllowed(Uri.parse("https://claras.page"), new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}));
        assertFalse(MainActivity.cameraAllowed(Uri.parse("https://claras.page.evil.test"), new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}));
        assertFalse(MainActivity.cameraAllowed(Uri.parse("http://claras.page"), new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}));
        assertFalse(MainActivity.cameraAllowed(Uri.parse("https://claras.page"), new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}));
    }

    @Test public void cameraProviderOnlyExposesItsTemporaryPhoto() throws Exception {
        Uri uri = CaptureProvider.create(context);
        try {
            try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri)) { output.write(new byte[]{1,2,3}); }
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) { assertEquals(1, input.read()); }
            assertEquals("image/jpeg", context.getContentResolver().getType(uri));
            for (String value : new String[]{"content://" + CaptureProvider.AUTHORITY + "/../native-session.xml", "content://other/fixture.jpg", "content://" + CaptureProvider.AUTHORITY + "/%2fsecret.jpg"}) {
                try { CaptureProvider.file(context, Uri.parse(value)); fail("Unsafe URI accepted"); }
                catch (java.io.FileNotFoundException expected) { }
            }
        } finally { CaptureProvider.file(context, uri).delete(); }
    }

    @Test public void opensLiveVideoThroughTheActualWebViewPermissionCallback() throws Exception {
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        Activity activity = launch();
        try {
            WebView web = web(activity);
            load(web, "https://claras.page/camera-test/", "<script>navigator.mediaDevices.getUserMedia({video:true,audio:false}).then(s=>{document.body.dataset.result=s.getVideoTracks()[0].readyState;s.getTracks().forEach(t=>t.stop());}).catch(e=>document.body.dataset.result=e.name);</script>");
            assertEquals("\"live\"", waitResult(web));
            load(web, "https://untrusted.invalid/", "<script>navigator.mediaDevices.getUserMedia({video:true}).then(s=>{s.getTracks().forEach(t=>t.stop());document.body.dataset.result='unexpected';}).catch(e=>document.body.dataset.result=e.name);</script>");
            waitForOrigin(web, "https://untrusted.invalid");
            assertEquals("\"NotAllowedError\"", waitResult(web));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void returnsAFullPhotoFromCaptureInputToTheWebPage() throws Exception {
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) return null;
                try {
                    Uri outputUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                    try (java.io.OutputStream output = context.getContentResolver().openOutputStream(outputUri)) {
                        Bitmap image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                        image.compress(Bitmap.CompressFormat.JPEG, 90, output);
                        image.recycle();
                    }
                } catch (Throwable error) { failure.set(error); }
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
            }
        };
        instrumentation.addMonitor(monitor);
        Activity activity = launch();
        try {
            WebView web = web(activity);
            load(web, "https://claras.page/capture-test/", "<input type=file accept=image/jpeg capture=environment style='width:100%;height:80vh' onchange=\"this.files[0].arrayBuffer().then(b=>{let a=new Uint8Array(b);document.body.dataset.result=a[0]===255&amp;&amp;a[1]===216?'photo':'invalid';}).catch(()=>document.body.dataset.result='unreadable')\">");
            SystemClock.sleep(500);
            int[] location = new int[2];
            instrumentation.runOnMainSync(() -> web.getLocationOnScreen(location));
            long now = SystemClock.uptimeMillis();
            instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, location[0] + 80, location[1] + 80, 0));
            instrumentation.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, location[0] + 80, location[1] + 80, 0));
            assertEquals("\"photo\"", waitResult(web));
            assertNull(failure.get());
        } finally { instrumentation.removeMonitor(monitor); instrumentation.runOnMainSync(activity::finish); }
    }

    private Activity launch() throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WebView web = web(activity);
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if ("\"complete\"".equals(evaluate(web, "document.readyState")) && !"\"about:blank\"".equals(evaluate(web, "location.href"))) break;
            SystemClock.sleep(100);
        }
        return activity;
    }
    private WebView web(Activity activity) {
        AtomicReference<WebView> found = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> found.set(find(activity.findViewById(android.R.id.content))));
        return found.get();
    }
    private WebView find(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            WebView found = find(((ViewGroup)view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private void load(WebView web, String origin, String body) {
        instrumentation.runOnMainSync(() -> web.loadDataWithBaseURL(origin, "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body>" + body + "</body></html>", "text/html", "UTF-8", origin));
    }
    private String waitResult(WebView web) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        String result;
        do { result = evaluate(web, "document.body?.dataset.result||''"); if (!"\"\"".equals(result) && !"null".equals(result)) return result; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < deadline);
        return result;
    }
    private void waitForOrigin(WebView web, String expectedOrigin) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        String expected = "\"" + expectedOrigin + "\"";
        String origin;
        do { origin = evaluate(web, "location.origin"); if (expected.equals(origin)) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(expected, origin);
    }
    private String evaluate(WebView web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
