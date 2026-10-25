package page.claras.pandemonium;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

final class HomeWidgetApi {
    static final class Unauthorized extends java.io.IOException { }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws java.io.IOException; }

    static JSONObject call(Context context, String method, JSONObject params) throws Exception {
        return call(context, method, params, url -> (HttpURLConnection) url.openConnection());
    }

    static JSONObject call(Context context, String method, JSONObject params, ConnectionFactory connections) throws Exception {
        if (!method.equals(MailWindowSource.METHOD)
                && !method.equals("calendar.widget.agenda") && !method.equals(WorkWeekSource.METHOD))
            throw new IllegalArgumentException("Unsupported widget read");
        String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN);
        if (SessionManager.cookieValue(cookie, SessionManager.SESSION) == null) throw new Unauthorized();
        HttpURLConnection connection = connections.open(new URL(SessionManager.ORIGIN + "/api/rpc"));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(35_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Pandemonium-Client", "android");
            connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(context));
            connection.setRequestProperty("Cookie", cookie);
            connection.setDoOutput(true);
            byte[] body = new JSONObject().put("jsonrpc", "2.0").put("protocolVersion", 1)
                .put("id", "android-widget").put("method", method).put("params", params)
                .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new Unauthorized();
            if (status != 200) throw new java.io.IOException("Widget read unavailable");
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                throw new java.io.IOException("Unexpected widget response");
            JSONObject result;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 1_048_576) throw new java.io.IOException("Widget response too large");
                    out.write(buffer, 0, count);
                }
                result = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
            if (result.has("error")) {
                JSONObject error = result.optJSONObject("error");
                String message = error == null ? "" : error.optString("message");
                if (method.equals(MailWindowSource.METHOD)) {
                    if (message.equals("email.widget.mail cursor is invalid or expired")) throw new MailWindowSource.CursorExpired();
                    if (message.equals("email.widget.mail pagination exceeds the explicit 50000-message safety bound")) throw new MailWindowSource.LimitExceeded();
                }
                throw new java.io.IOException("Widget data unavailable");
            }
            JSONObject value = result.optJSONObject("result");
            if (value == null) throw new java.io.IOException("Missing widget result");
            return value;
        } finally { connection.disconnect(); }
    }
}
