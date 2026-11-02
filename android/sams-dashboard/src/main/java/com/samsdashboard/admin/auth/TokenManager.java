package com.samsdashboard.admin.auth;

import android.content.Context;
import android.content.SharedPreferences;

public class TokenManager {
    private static final String PREF_NAME = "sams_dashboard_secure_prefs";
    private static final String KEY_ACCESS_TOKEN = "jwt_access_token";
    private static final String KEY_REFRESH_TOKEN = "jwt_refresh_token";
    private static final String KEY_USERNAME = "admin_username";

    private final SharedPreferences prefs;

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveTokens(String accessToken, String refreshToken, String username) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_USERNAME, username)
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "Administrator");
    }

    public boolean isLoggedIn() {
        return getAccessToken() != null;
    }

    public void clearTokens() {
        prefs.edit().clear().apply();
    }
}
