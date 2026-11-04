package com.samsdashboard.admin.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.samsdashboard.admin.DashboardActivity;

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

/**
 * LoginActivity v3.0:
 * Zero hardcoded passwords or insecure credential autofill.
 * Supports:
 * 1. First-Time Administrator Setup (Argon2id hashing + one-time Recovery Key display).
 * 2. Standard Administrator Sign-In (short-lived access token + refresh token stored in Android Keystore).
 * 3. Deployment Recovery Key Password Reset.
 */
public class LoginActivity extends Activity {

    private static final String PREFS_SERVER = "sams_dashboard_server_prefs_v3";
    private static final String KEY_BASE_URL = "backend_base_url";
    public static final String DEFAULT_CLOUD_URL = "https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app";

    private TextView diagStatusText;
    private TextView modeSubtitleText;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etRecoveryKey;
    private Button btnPrimaryAction;
    private Button btnToggleResetMode;
    private TokenManager tokenManager;

    private boolean isSetupRequired = false;
    private boolean isResetMode = false;

    private final ExecutorService executor = Executors.newCachedThreadPool();
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

        tokenManager = new TokenManager(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#080B11"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 64, 48, 64);

        TextView title = new TextView(this);
        title.setText("SAM'S DASHBOARD v3.0");
        title.setTextColor(Color.parseColor("#00F0FF"));
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        modeSubtitleText = new TextView(this);
        modeSubtitleText.setText("Checking server authentication state...");
        modeSubtitleText.setTextColor(Color.parseColor("#E2E8F0"));
        modeSubtitleText.setTextSize(13);
        modeSubtitleText.setPadding(0, 8, 0, 12);
        root.addView(modeSubtitleText);

        diagStatusText = new TextView(this);
        diagStatusText.setText("Connecting to " + getBaseUrl(this) + "...");
        diagStatusText.setTextColor(Color.parseColor("#10B981"));
        diagStatusText.setTextSize(11);
        diagStatusText.setPadding(0, 0, 0, 24);
        root.addView(diagStatusText);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#151E2E"));
        card.setPadding(36, 36, 36, 36);

        TextView userLabel = new TextView(this);
        userLabel.setText("ADMINISTRATOR USERNAME");
        userLabel.setTextColor(Color.parseColor("#94A3B8"));
        userLabel.setTextSize(11);
        card.addView(userLabel);

        etUsername = new EditText(this);
        etUsername.setHint("admin");
        etUsername.setText("admin");
        etUsername.setTextColor(Color.WHITE);
        etUsername.setHintTextColor(Color.parseColor("#64748B"));
        etUsername.setBackgroundColor(Color.parseColor("#0A0E17"));
        etUsername.setPadding(24, 20, 24, 20);
        card.addView(etUsername);

        etRecoveryKey = new EditText(this);
        etRecoveryKey.setHint("Deployment Recovery Key (SR3-XXXX-XXXX-XXXX)");
        etRecoveryKey.setTextColor(Color.WHITE);
        etRecoveryKey.setHintTextColor(Color.parseColor("#64748B"));
        etRecoveryKey.setBackgroundColor(Color.parseColor("#0A0E17"));
        etRecoveryKey.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        recLp.setMargins(0, 20, 0, 0);
        etRecoveryKey.setLayoutParams(recLp);
        etRecoveryKey.setVisibility(View.GONE);
        card.addView(etRecoveryKey);

        TextView passLabel = new TextView(this);
        passLabel.setText("\nADMINISTRATOR PASSWORD (MIN 8 CHARS)");
        passLabel.setTextColor(Color.parseColor("#94A3B8"));
        passLabel.setTextSize(11);
        card.addView(passLabel);

        etPassword = new EditText(this);
        etPassword.setHint("Enter administrator password");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setTextColor(Color.WHITE);
        etPassword.setHintTextColor(Color.parseColor("#64748B"));
        etPassword.setBackgroundColor(Color.parseColor("#0A0E17"));
        etPassword.setPadding(24, 20, 24, 20);
        card.addView(etPassword);

        btnPrimaryAction = new Button(this);
        btnPrimaryAction.setText("SIGN IN");
        btnPrimaryAction.setBackgroundColor(Color.parseColor("#00F0FF"));
        btnPrimaryAction.setTextColor(Color.parseColor("#080B11"));
        btnPrimaryAction.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnLp.setMargins(0, 28, 0, 12);
        btnPrimaryAction.setLayoutParams(btnLp);
        btnPrimaryAction.setOnClickListener(v -> executeAuthRequest());
        card.addView(btnPrimaryAction);

        btnToggleResetMode = new Button(this);
        btnToggleResetMode.setText("FORGOT PASSWORD? RESET WITH RECOVERY KEY");
        btnToggleResetMode.setBackgroundColor(Color.parseColor("#1E293B"));
        btnToggleResetMode.setTextColor(Color.parseColor("#94A3B8"));
        btnToggleResetMode.setTextSize(10);
        btnToggleResetMode.setOnClickListener(v -> {
            isResetMode = !isResetMode;
            updateUiMode();
        });
        card.addView(btnToggleResetMode);

        root.addView(card);
        scroll.addView(root);
        setContentView(scroll);

        checkServerSetupStatus();
    }

    private void updateUiMode() {
        if (isSetupRequired) {
            modeSubtitleText.setText("First-Time Administrator Setup (Argon2id)");
            etRecoveryKey.setVisibility(View.GONE);
            etPassword.setHint("Create strong password (min 8 chars)");
            btnPrimaryAction.setText("CREATE ADMIN ACCOUNT");
            btnToggleResetMode.setVisibility(View.GONE);
        } else if (isResetMode) {
            modeSubtitleText.setText("Reset Password via Deployment Recovery Key");
            etRecoveryKey.setVisibility(View.VISIBLE);
            etPassword.setHint("Enter new password (min 8 chars)");
            btnPrimaryAction.setText("RESET PASSWORD & SIGN IN");
            btnToggleResetMode.setVisibility(View.VISIBLE);
            btnToggleResetMode.setText("BACK TO STANDARD SIGN IN");
        } else {
            modeSubtitleText.setText("Administrator Sign-In (Android Keystore Protected)");
            etRecoveryKey.setVisibility(View.GONE);
            etPassword.setHint("Enter administrator password");
            btnPrimaryAction.setText("SIGN IN");
            btnToggleResetMode.setVisibility(View.VISIBLE);
            btnToggleResetMode.setText("RESET PASSWORD WITH RECOVERY KEY");
        }
    }

    private void checkServerSetupStatus() {
        final String baseUrl = getBaseUrl(this);
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/v1/auth/status");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    JSONObject json = parseJsonResponse(conn);
                    final boolean setupReq = json.optBoolean("setup_required", false);
                    final String algo = json.optString("hash_algorithm", "argon2id");
                    final String adminUser = json.optString("admin_username", "admin");
                    mainHandler.post(() -> {
                        isSetupRequired = setupReq;
                        if (adminUser != null && !adminUser.isEmpty() && !"null".equals(adminUser)) {
                            etUsername.setText(adminUser);
                        }
                        diagStatusText.setText("Server Online (" + algo.toUpperCase() + ") · " + baseUrl);
                        diagStatusText.setTextColor(Color.parseColor("#10B981"));
                        updateUiMode();
                    });
                } else {
                    mainHandler.post(() -> {
                        diagStatusText.setText("Server returned HTTP " + code + " at " + baseUrl);
                        diagStatusText.setTextColor(Color.parseColor("#F59E0B"));
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    diagStatusText.setText("Connection diagnostic error: " + e.getMessage());
                    diagStatusText.setTextColor(Color.parseColor("#FF0055"));
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void executeAuthRequest() {
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();
        final String recoveryKey = etRecoveryKey.getText().toString().trim();

        if (username.length() < 3) {
            Toast.makeText(this, "Username must be at least 3 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 8) {
            Toast.makeText(this, "Password must be at least 8 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isResetMode && recoveryKey.isEmpty()) {
            Toast.makeText(this, "Enter your deployment Recovery Key", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPrimaryAction.setEnabled(false);
        btnPrimaryAction.setText("AUTHENTICATING...");

        executor.execute(() -> {
            String baseUrl = getBaseUrl(LoginActivity.this);
            HttpURLConnection conn = null;
            try {
                String endpoint = "/api/v1/auth/login";
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                if (isSetupRequired) {
                    endpoint = "/api/v1/auth/setup";
                    payload.put("password", password);
                } else if (isResetMode) {
                    endpoint = "/api/v1/auth/reset-password";
                    payload.put("recovery_key", recoveryKey);
                    payload.put("new_password", password);
                } else {
                    payload.put("password", password);
                }

                byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                URL url = new URL(baseUrl + endpoint);
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
                    JSONObject json = parseJsonResponse(conn);
                    String accessToken = json.optString("access_token", "");
                    String refreshToken = json.optString("refresh_token", "");
                    final String oneTimeRecoveryKey = json.optString("recovery_key", "");
                    tokenManager.saveTokens(accessToken, refreshToken, username);

                    mainHandler.post(() -> {
                        btnPrimaryAction.setEnabled(true);
                        updateUiMode();
                        if (!oneTimeRecoveryKey.isEmpty()) {
                            showRecoveryKeyDialogAndProceed(oneTimeRecoveryKey);
                        } else {
                            navigateToDashboard();
                        }
                    });
                } else {
                    String errBody = readStream(conn.getErrorStream());
                    String detail = "HTTP " + code;
                    try {
                        detail = new JSONObject(errBody).optString("detail", detail);
                    } catch (Exception ignored) {}
                    final String finalDetail = detail;
                    mainHandler.post(() -> {
                        btnPrimaryAction.setEnabled(true);
                        updateUiMode();
                        diagStatusText.setText("Auth Error: " + finalDetail);
                        diagStatusText.setTextColor(Color.parseColor("#FF0055"));
                        Toast.makeText(LoginActivity.this, finalDetail, Toast.LENGTH_LONG).show();
                        checkServerSetupStatus();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    btnPrimaryAction.setEnabled(true);
                    updateUiMode();
                    diagStatusText.setText("Connection Error: " + e.getMessage());
                    diagStatusText.setTextColor(Color.parseColor("#FF0055"));
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void showRecoveryKeyDialogAndProceed(final String recoveryKey) {
        new AlertDialog.Builder(this)
                .setTitle("Save Your Deployment Recovery Key")
                .setCancelable(false)
                .setMessage("Administrator account created and hashed with Argon2id!\n\n" +
                        "One-Time Recovery Key:\n" + recoveryKey + "\n\n" +
                        "Save this key now. It is required if you ever need to reset your administrator password.")
                .setPositiveButton("COPY KEY & CONTINUE", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("ScreamRunnerRecoveryKey", recoveryKey));
                    }
                    Toast.makeText(this, "Recovery Key copied to clipboard", Toast.LENGTH_SHORT).show();
                    navigateToDashboard();
                })
                .show();
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(LoginActivity.this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private static JSONObject parseJsonResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        String contentType = conn.getContentType();
        if (code < 200 || code >= 300) {
            throw new java.io.IOException("Server returned HTTP " + code);
        }
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
            throw new java.io.IOException("Server returned " + (contentType == null ? "an unknown content type" : contentType)
                    + " instead of JSON. Verify the backend URL and deployment.");
        }
        String body = readStream(conn.getInputStream());
        try {
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new java.io.IOException("Server returned invalid JSON. Verify the backend URL and deployment.", e);
        }
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
