package com.subtitletool.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local API keys, AES-256-GCM under AndroidKeyStore, not exported or backed up. */
public final class SecureKeys {
    private static final String ALIAS = "subtitle_tool_keys_v1";
    private final SharedPreferences prefs;
    public SecureKeys(Context context) {
        prefs = context.getSharedPreferences("subtitle_tool_secret_settings", Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(false).build());
            gen.generateKey();
        }
        return ((SecretKey) ks.getKey(ALIAS, null));
    }
    public void save(String slot, String value) throws Exception {
        if (!slot.matches("deepgram|assemblyai|deepseek|glm|kimi_cn|kimi_intl|siliconflow|volcengine|bailian|custom")) throw new IllegalArgumentException("Invalid key slot");
        if (value == null || value.trim().isEmpty()) return; // don't overwrite a saved key with a blank field
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!prefs.edit().putString(slot, packed).commit()) throw new IllegalStateException("Save failed");
    }
    public String load(String slot) throws Exception {
        String packed = prefs.getString(slot, "");
        if (packed.isEmpty()) return "";
        String[] parts = packed.split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("Saved credential damaged");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
    }
    public boolean has(String slot) { return !prefs.getString(slot, "").isEmpty(); }
    public void clear() { prefs.edit().clear().apply(); }
}
