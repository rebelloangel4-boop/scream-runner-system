package com.samsdashboard.admin.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.samsdashboard.admin.DashboardActivity;
import com.samsdashboard.admin.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {

    private static final String PREFS_SERVER = "sams_dashboard_server_prefs";
    private static final String KEY_BASE_URL = "backend_base_url";
    public static final String DEFAULT_CLOUD_URL = "https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app";

    private EditText etUsername;
    private EditText etPassword;
    private Button btnLogin;
    private TokenManager tokenManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static String getBaseUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_SERVER, Context.MODE_PRIVATE);
        return prefs.getString(KEY_BASE_URL, DEFAULT_CLOUD_URL);
    }

    public static void setBaseUrl(Context context, String url) {
        String cleaned = url.trim();
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        context.getSharedPreferences(PREFS_SERVER, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BASE_URL, cleaned)
                .apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        tokenManager = new TokenManager(this);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_submit_login);

        etUsername.setText("admin");
        btnLogin.setOnClickListener(v -> performLogin());
    }

    private void performLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter console username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        btnLogin.setText("SIGNING IN...");

        executor.execute(() -> {
            boolean success = false;
            String errorMsg = "Sign in failed";
            String baseUrl = getBaseUrl(LoginActivity.this);
            HttpURLConnection conn = null;

            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                payload.put("password", password);
                byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL(baseUrl + "/api/v1/auth/login");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setFixedLengthStreamingMode(bodyBytes.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String respStr = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(respStr);
                    String accessToken = json.optString("access_token", "");
                    String refreshToken = json.optString("refresh_token", "");
                    tokenManager.saveTokens(accessToken, refreshToken, username);
                    success = true;
                } else if (code == 429) {
                    errorMsg = "HTTP 429: Too many login attempts. Wait 60s.";
                } else {
                    errorMsg = "HTTP " + code + ": Invalid credentials";
                }
            } catch (Exception e) {
                errorMsg = "Network error (" + baseUrl + "): " + e.getMessage();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            final boolean isOk = success;
            final String msg = errorMsg;
            mainHandler.post(() -> {
                btnLogin.setEnabled(true);
                btnLogin.setText("SIGN IN");
                if (isOk) {
                    Toast.makeText(LoginActivity.this, "Authenticated with Telemetry Server", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(LoginActivity.this, DashboardActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                } else {
                    Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
