package page.claras.pandemonium;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed HTTPS origin, no redirects, bounded time and response size. */
final class SessionTransport implements SessionManager.Transport {
    private final String userAgent;
    SessionTransport(String userAgent) { this.userAgent = userAgent; }

    @Override public SessionManager.Reply post(String endpoint, String cookie, JSONObject body) throws Exception {
        if (!"enroll".equals(endpoint) && !"refresh".equals(endpoint)) throw new IllegalArgumentException("Unknown session action");
        HttpURLConnection connection = (HttpURLConnection) new URL(SessionManager.ORIGIN + "/api/android/" + endpoint).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Pandemonium-Client", "android");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Cookie", cookie);
            connection.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(payload); }
            int status = connection.getResponseCode();
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(header.getKey())) cookies.addAll(header.getValue());
            }
            return new SessionManager.Reply(status, status == 200 ? readResponse(connection.getInputStream()) : new JSONObject(), cookies);
        } finally { connection.disconnect(); }
    }

    private static JSONObject readResponse(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 16_384) throw new java.io.IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return new JSONObject(output.toString("UTF-8"));
        }
    }
}
