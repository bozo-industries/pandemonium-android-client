package page.claras.pandemonium;

import android.net.Uri;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read-only access to Finance's existing static merchant wallet cards. */
final class LoyaltyWalletClient {
    private static final int MAX_RESPONSE_BYTES = 131_072;
    private static final String FINANCE_WALLET = SessionManager.ORIGIN + "/finance/merchant-wallet/card";

    private LoyaltyWalletClient() { }

    static JSONObject getCard(String providerId, String cookie, String userAgent) throws Exception {
        if (!LoyaltyCodeStore.supports(providerId)) throw new IllegalArgumentException("Unsupported loyalty provider");
        String url = FINANCE_WALLET + "?providerId=" + Uri.encode(providerId);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Pandemonium-Client", "android");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Cookie", cookie);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) return null;
            return new JSONObject(readBounded(connection.getInputStream()));
        } finally { connection.disconnect(); }
    }

    static boolean mergeCard(String requestedProvider, JSONObject card, Map<String, String> payloads) {
        if (!LoyaltyCodeStore.supports(requestedProvider) || card == null
            || !requestedProvider.equals(card.optString("providerId"))) return false;
        String state = card.optString("state", "");
        if ("missing".equals(state)) {
            payloads.remove(requestedProvider);
            return true;
        }
        if (!"ready".equals(state)) return false;
        if (!card.isNull("expiresAt") && !card.optString("expiresAt", "").trim().isEmpty()) {
            payloads.remove(requestedProvider);
            return true;
        }
        String displayCode = text(card, "displayCode");
        String qrContent = text(card, "qrContent");
        if (LoyaltyCodeStore.LIDL.equals(requestedProvider) && qrContent != null && displayCode != null
            && !qrContent.equals(displayCode) && !qrContent.equals(displayCode + "1")) return false;
        String payload = first(qrContent, text(card, "barcodeContent"), displayCode);
        if (payload == null || payload.length() > 4096) return false;
        payloads.put(requestedProvider, payload);
        return true;
    }

    private static String text(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        String value = object.optString(key, "").trim();
        return value.isEmpty() || value.length() > 4096 ? null : value;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return null;
    }

    private static String readBounded(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new java.io.IOException("Wallet response too large");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
