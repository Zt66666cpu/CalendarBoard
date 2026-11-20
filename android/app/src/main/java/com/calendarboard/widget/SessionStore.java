package com.calendarboard.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionStore {
    private static final String PREFS = "calendar_widget_private";
    private static final String KEY_ALIAS = "calendar_widget_session_v1";
    private static final AtomicLong REVISION = new AtomicLong();

    private SessionStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    static synchronized void saveSession(Context context, JSONObject session) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs(context).edit().putString("session", value).apply();
    }

    static long revision() { return REVISION.get(); }

    static synchronized JSONObject session(Context context) {
        String value = prefs(context).getString("session", null);
        if (value == null) return null;
        try {
            String[] parts = value.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception error) {
            prefs(context).edit().remove("session").apply();
            return null;
        }
    }

    static synchronized void saveCache(Context context, JSONObject data) {
        prefs(context).edit().putString("today_cache", data.toString()).apply();
    }

    static synchronized JSONObject cache(Context context) {
        try { return new JSONObject(prefs(context).getString("today_cache", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    static synchronized void clear(Context context) {
        REVISION.incrementAndGet();
        prefs(context).edit().clear().apply();
    }
}

