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

    static JSONObject call(Context context, String method, JSONObject params) throws Exception {
        if (!method.equals("email.widget.inbox") && !method.equals("email.widget.message")
                && !method.equals("calendar.widget.agenda") && !method.equals(WorkWeekSource.METHOD))
            throw new IllegalArgumentException("Unsupported widget read");
        String cookie = CookieManager.getInstance().getCookie(SessionManager.ORIGIN);
        if (SessionManager.cookieValue(cookie, SessionManager.SESSION) == null) throw new Unauthorized();
        HttpURLConnection connection = (HttpURLConnection) new URL(SessionManager.ORIGIN + "/api/rpc").openConnection();
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
            if (result.has("error")) throw new java.io.IOException("Widget data unavailable");
            JSONObject value = result.optJSONObject("result");
            if (value == null) throw new java.io.IOException("Missing widget result");
            return value;
        } finally { connection.disconnect(); }
    }
}
