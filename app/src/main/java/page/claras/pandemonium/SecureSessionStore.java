package page.claras.pandemonium;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only ciphertext leaves Android Keystore-backed encryption; app backup is disabled. */
final class SecureSessionStore {
    private final String alias;
    private final SharedPreferences preferences;

    SecureSessionStore(Context context) {
        this(context, "native-session", "pandemonium.refresh.v1");
    }

    SecureSessionStore(Context context, String name, String alias) {
        this.alias = alias;
        preferences = context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    JSONObject read() throws Exception {
        String value = preferences.getString("encrypted", null);
        if (value == null) return null;
        JSONObject stored = new JSONObject(value);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)));
        byte[] plaintext = cipher.doFinal(Base64.decode(stored.getString("data"), Base64.NO_WRAP));
        return new JSONObject(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));
    }

    void save(JSONObject state) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject value = new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!preferences.edit().putString("encrypted", value.toString()).commit()) throw new java.io.IOException("Session persistence failed");
    }

    void clear() throws java.io.IOException {
        if (!preferences.edit().clear().commit()) throw new java.io.IOException("Session removal failed");
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
