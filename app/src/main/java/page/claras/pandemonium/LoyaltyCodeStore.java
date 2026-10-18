package page.claras.pandemonium;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persists only static loyalty QR payloads, encrypted with a separate Keystore key. */
final class LoyaltyCodeStore {
    static final String REWE = "rewe";
    static final String LIDL = "lidl-plus";
    static final String ACTION = "action";
    private static final String[] PROVIDERS = {REWE, LIDL, ACTION};
    private static final String ALIAS = "pandemonium.loyalty-codes.v1";
    private final SharedPreferences preferences;

    LoyaltyCodeStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("loyalty-widget", Context.MODE_PRIVATE);
    }

    synchronized Map<String, String> read() throws Exception {
        String value = preferences.getString("encrypted", null);
        if (value == null) return Collections.emptyMap();
        JSONObject stored = new JSONObject(value);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)));
        JSONObject payloads = new JSONObject(new String(cipher.doFinal(Base64.decode(stored.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
        Map<String, String> result = new HashMap<>();
        for (String provider : PROVIDERS) {
            String payload = payloads.optString(provider, "").trim();
            if (!payload.isEmpty() && payload.length() <= 4096) result.put(provider, payload);
        }
        return result;
    }

    synchronized void replace(Map<String, String> payloads) throws Exception {
        JSONObject clean = new JSONObject();
        for (String provider : PROVIDERS) {
            String payload = payloads.get(provider);
            if (payload == null) continue;
            payload = payload.trim();
            if (!payload.isEmpty() && payload.length() <= 4096) clean.put(provider, payload);
        }
        if (clean.length() == 0) {
            clear();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(clean.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject stored = new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!preferences.edit().putString("encrypted", stored.toString()).commit()) {
            throw new java.io.IOException("Loyalty code persistence failed");
        }
    }

    synchronized void clear() throws java.io.IOException {
        if (!preferences.edit().remove("encrypted").commit()) throw new java.io.IOException("Loyalty code removal failed");
    }

    static boolean supports(String provider) {
        for (String value : PROVIDERS) if (value.equals(provider)) return true;
        return false;
    }

    static String label(String provider) {
        if (REWE.equals(provider)) return "REWE";
        if (LIDL.equals(provider)) return "Lidl Plus";
        if (ACTION.equals(provider)) return "Action";
        return "";
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
