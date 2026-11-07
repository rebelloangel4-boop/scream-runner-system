package com.samsdashboard.admin.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * TokenManager v3.0:
 * Encrypts administrator access & refresh tokens using Android Keystore (AES/GCM/NoPadding)
 * so tokens are never stored in plaintext on disk.
 */
public class TokenManager {
    private static final String PREF_NAME = "sams_dashboard_secure_prefs_v3";
    private static final String KEY_ACCESS_TOKEN = "enc_jwt_access_token";
    private static final String KEY_REFRESH_TOKEN = "enc_jwt_refresh_token";
    private static final String KEY_USERNAME = "admin_username";
    private static final String KEYSTORE_ALIAS = "sams_dashboard_v3_aes_key";

    private final SharedPreferences prefs;

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureKeystoreKey();
    }

    private void ensureKeystoreKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(KEYSTORE_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build();
                keyGenerator.init(spec);
                keyGenerator.generateKey();
            }
        } catch (Exception ignored) {}
    }

    private String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey secretKey = ((KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                byte[] iv = cipher.getIV();
                byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipherText, Base64.NO_WRAP);
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Secure credential storage unavailable; unlock this phone and retry");
    }

    private String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && stored.contains(":")) {
            try {
                String[] parts = stored.split(":", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey secretKey = ((KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
                byte[] plainBytes = cipher.doFinal(cipherText);
                return new String(plainBytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null; // Never accept legacy plaintext/base64 credentials.

    }

    public void saveTokens(String accessToken, String refreshToken, String username) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, encrypt(accessToken))
                .putString(KEY_REFRESH_TOKEN, encrypt(refreshToken))
                .putString(KEY_USERNAME, username)
                .apply();
    }

    public String getAccessToken() {
        return decrypt(prefs.getString(KEY_ACCESS_TOKEN, null));
    }

    public String getRefreshToken() {
        return decrypt(prefs.getString(KEY_REFRESH_TOKEN, null));
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "admin");
    }

    public boolean isLoggedIn() {
        String tok = getAccessToken();
        return tok != null && !tok.isEmpty();
    }

    public void clearTokens() {
        prefs.edit().clear().apply();
    }
}
