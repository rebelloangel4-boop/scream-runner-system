package com.samsdashboard.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.samsdashboard.admin.auth.LoginActivity;
import com.samsdashboard.admin.auth.TokenManager;
import com.samsdashboard.admin.player.AudioPlayerController;
import com.samsdashboard.admin.ui.AudioWaveformView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DashboardActivity v3.0:
 * - Secure administrator session management with automatic refresh-token rotation and logout revocation.
 * - Real authorized recordings only (zero fake/demo data).
 * - Missed-event sequence replay (/api/v1/events/missed?since_seq=...) across network reconnects.
 * - Interactive 50-bar waveform scrubbing, variable-speed playback (0.75x–2.0x), > -10dB peak filter,
 *   authenticated export, and clip deletion.
 */
public class DashboardActivity extends Activity {

    private TokenManager tokenManager;
    private AudioPlayerController playerController;
    private AudioWaveformView waveformView;
    private TextView statusText;
    private TextView deviceSummaryText;
    private TextView playbackInfoText;
    private TextView selectedSegmentTitle;
    private EditText searchInput;
    private Button filterHighlightBtn;
    private LinearLayout deviceListContainer;
    private LinearLayout sessionListContainer;
    private LinearLayout segmentListContainer;
    private Button playBtn;
    private Button authBtn;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private float currentSpeed = 1.0f;
    private boolean isPlaying = false;
    private boolean autoSyncActive = false;
    private boolean highlightsOnlyFilter = false;
    private String searchQuery = "";
    private String selectedSessionId = null;
    private String selectedSegmentId = null;
    private double selectedPeakDb = -12.0;
    private int lastSeenEventSeq = 0;

    private JSONArray cachedSessions = new JSONArray();
    private JSONArray cachedSegments = new JSONArray();

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoSyncActive || !tokenManager.isLoggedIn()) return;
            syncMissedEventsAndRefresh();
            mainHandler.postDelayed(this, 6000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tokenManager = new TokenManager(this);
        playerController = new AudioPlayerController(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#080B11"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 48);

        LinearLayout topHeader = new LinearLayout(this);
        topHeader.setOrientation(LinearLayout.HORIZONTAL);

        TextView titleText = new TextView(this);
        titleText.setText("SAM'S DASHBOARD v3.0");
        titleText.setTextColor(Color.parseColor("#00F0FF"));
        titleText.setTextSize(20);
        titleText.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleText.setLayoutParams(titleLp);
        topHeader.addView(titleText);

        authBtn = new Button(this);
        updateAuthButtonState();
        authBtn.setOnClickListener(v -> handleAuthAction());
        topHeader.addView(authBtn);

        root.addView(topHeader);

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(0, 12, 0, 12);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("SYNC TELEMETRY");
        refreshBtn.setBackgroundColor(Color.parseColor("#151E2E"));
        refreshBtn.setTextColor(Color.parseColor("#00F0FF"));
        refreshBtn.setOnClickListener(v -> {
            if (!tokenManager.isLoggedIn()) {
                Toast.makeText(this, "Sign in or complete first-time setup to view recordings", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, LoginActivity.class));
                return;
            }
            autoSyncActive = true;
            fetchLiveDashboardData(true);
            mainHandler.removeCallbacks(syncRunnable);
            mainHandler.postDelayed(syncRunnable, 6000);
        });
        actionBar.addView(refreshBtn);

        Button serverBtn = new Button(this);
        serverBtn.setText("SERVER URL");
        serverBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        serverBtn.setTextColor(Color.parseColor("#94A3B8"));
        serverBtn.setOnClickListener(v -> showServerUrlDialog());
        actionBar.addView(serverBtn);

        Button pairBtn = new Button(this);
        pairBtn.setText("PAIR DEVICE");
        pairBtn.setOnClickListener(v -> executor.execute(() -> {
            try {
                JSONObject response = new JSONObject(httpGetAuthenticated(LoginActivity.getBaseUrl(this) + "/api/v1/devices/pairing-code"));
                String code = response.getString("pairing_code");
                mainHandler.post(() -> new AlertDialog.Builder(this).setTitle("Private device pairing code")
                        .setMessage("Enter this code in Leo's Scream Runner settings. Pairing does not authorize sharing.\n\n" + code)
                        .setPositiveButton("CLOSE", null).show());
            } catch (Exception e) { mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
        }));
        actionBar.addView(pairBtn);
        root.addView(actionBar);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#10B981"));
        statusText.setTextSize(13);
        statusText.setPadding(0, 4, 0, 8);
        root.addView(statusText);

        deviceSummaryText = new TextView(this);
        deviceSummaryText.setTextColor(Color.parseColor("#94A3B8"));
        deviceSummaryText.setTextSize(12);
        deviceSummaryText.setPadding(0, 0, 0, 10);
        root.addView(deviceSummaryText);

        deviceListContainer = new LinearLayout(this);
        deviceListContainer.setOrientation(LinearLayout.VERTICAL);
        deviceListContainer.setPadding(0, 0, 0, 16);
        root.addView(deviceListContainer);

        // Waveform & Audio Playback Inspector Card
        LinearLayout playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setBackgroundColor(Color.parseColor("#151E2E"));
        playerCard.setPadding(24, 24, 24, 24);

        selectedSegmentTitle = new TextView(this);
        selectedSegmentTitle.setText("WAVEFORM & ACOUSTIC INSPECTOR (Select an authorized recording below)");
        selectedSegmentTitle.setTextColor(Color.parseColor("#00F0FF"));
        selectedSegmentTitle.setTextSize(13);
        selectedSegmentTitle.setTypeface(null, Typeface.BOLD);
        playerCard.addView(selectedSegmentTitle);

        waveformView = new AudioWaveformView(this);
        waveformView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220
        ));
        waveformView.setOnScrubListener(progress -> playerController.seekTo(progress));
        playerCard.addView(waveformView);

        playbackInfoText = new TextView(this);
        playbackInfoText.setText("No recording selected · Sign in and select a recording clip below");
        playbackInfoText.setTextColor(Color.parseColor("#E2E8F0"));
        playbackInfoText.setTextSize(12);
        playbackInfoText.setPadding(0, 12, 0, 16);
        playerCard.addView(playbackInfoText);

        LinearLayout playerControls = new LinearLayout(this);
        playerControls.setOrientation(LinearLayout.HORIZONTAL);

        playBtn = new Button(this);
        playBtn.setText("PLAY SELECTED");
        playBtn.setBackgroundColor(Color.parseColor("#00F0FF"));
        playBtn.setTextColor(Color.parseColor("#080B11"));
        playBtn.setOnClickListener(v -> togglePlaySelected());
        playerControls.addView(playBtn);

        Button speedBtn = new Button(this);
        speedBtn.setText("SPEED: 1.0x");
        speedBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        speedBtn.setTextColor(Color.parseColor("#00F0FF"));
        speedBtn.setOnClickListener(v -> cycleSpeed(speedBtn));
        playerControls.addView(speedBtn);

        Button downloadBtn = new Button(this);
        downloadBtn.setText("EXPORT CLIP");
        downloadBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        downloadBtn.setTextColor(Color.parseColor("#10B981"));
        downloadBtn.setOnClickListener(v -> downloadSelectedClip());
        playerControls.addView(downloadBtn);

        Button deleteBtn = new Button(this);
        deleteBtn.setText("DELETE CLIP");
        deleteBtn.setBackgroundColor(Color.parseColor("#3B0D1E"));
        deleteBtn.setTextColor(Color.parseColor("#FF0055"));
        deleteBtn.setOnClickListener(v -> deleteSelectedClip());
        playerControls.addView(deleteBtn);

        playerCard.addView(playerControls);
        root.addView(playerCard);

        // Search & Loudness Filter Bar
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setPadding(0, 20, 0, 8);

        searchInput = new EditText(this);
        searchInput.setHint("Search session or device...");
        searchInput.setHintTextColor(Color.parseColor("#64748B"));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(12);
        searchInput.setBackgroundColor(Color.parseColor("#151E2E"));
        searchInput.setPadding(20, 16, 20, 16);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        searchLp.setMargins(0, 0, 12, 0);
        searchInput.setLayoutParams(searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString().trim().toLowerCase(Locale.US);
                renderSessionsList(cachedSessions);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        filterBar.addView(searchInput);

        filterHighlightBtn = new Button(this);
        filterHighlightBtn.setText("FILTER: ALL CLIPS");
        filterHighlightBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        filterHighlightBtn.setTextColor(Color.parseColor("#00F0FF"));
        filterHighlightBtn.setTextSize(11);
        filterHighlightBtn.setOnClickListener(v -> {
            highlightsOnlyFilter = !highlightsOnlyFilter;
            filterHighlightBtn.setText(highlightsOnlyFilter ? "PEAKS ONLY (> -10dB)" : "FILTER: ALL CLIPS");
            filterHighlightBtn.setTextColor(Color.parseColor(highlightsOnlyFilter ? "#FF0055" : "#00F0FF"));
            renderSegmentsList(cachedSegments);
        });
        filterBar.addView(filterHighlightBtn);

        root.addView(filterBar);

        TextView sessionHeader = new TextView(this);
        sessionHeader.setText("\nAUTHORIZED RECORDING SESSIONS (TAP TO INSPECT)");
        sessionHeader.setTextColor(Color.parseColor("#94A3B8"));
        sessionHeader.setTextSize(13);
        sessionHeader.setTypeface(null, Typeface.BOLD);
        sessionHeader.setPadding(0, 8, 0, 8);
        root.addView(sessionHeader);

        sessionListContainer = new LinearLayout(this);
        sessionListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionListContainer);

        TextView segmentHeader = new TextView(this);
        segmentHeader.setText("\nRECORDED SPEECH CLIPS & LOUDNESS PEAKS (> -10 dB)");
        segmentHeader.setTextColor(Color.parseColor("#94A3B8"));
        segmentHeader.setTextSize(13);
        segmentHeader.setTypeface(null, Typeface.BOLD);
        segmentHeader.setPadding(0, 16, 0, 8);
        root.addView(segmentHeader);

        segmentListContainer = new LinearLayout(this);
        segmentListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(segmentListContainer);

        scrollView.addView(root);
        setContentView(scrollView);

        playerController.setCallback(new AudioPlayerController.PlayerCallback() {
            @Override
            public void onProgress(int currentMs, int durationMs, float progressRatio) {
                waveformView.setProgress(progressRatio);
                playbackInfoText.setText(String.format(Locale.US, "Playing: %dms / %dms (%.2fx) · Peak: %.1f dB",
                        currentMs, durationMs, currentSpeed, selectedPeakDb));
            }

            @Override
            public void onPlaybackCompleted() {
                isPlaying = false;
                playBtn.setText("PLAY SELECTED");
                waveformView.setProgress(0.0f);
                playbackInfoText.setText("Playback completed · Speed: " + currentSpeed + "x");
            }

            @Override
            public void onError(String message) {
                isPlaying = false;
                playBtn.setText("PLAY SELECTED");
                Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        refreshUiForAuthState();
    }

    private void refreshUiForAuthState() {
        updateAuthButtonState();
        if (tokenManager.isLoggedIn()) {
            statusText.setText("Signed in as " + tokenManager.getUsername() + " · Syncing authorized recordings...");
            autoSyncActive = true;
            fetchLiveDashboardData(false);
            mainHandler.removeCallbacks(syncRunnable);
            mainHandler.postDelayed(syncRunnable, 6000);
        } else {
            statusText.setText("Authentication Required · Tap SIGN IN / SETUP (top right) to access recordings");
            deviceSummaryText.setText("Not signed in · Zero fake or unauthenticated recordings displayed");
            deviceListContainer.removeAllViews();
            sessionListContainer.removeAllViews();
            segmentListContainer.removeAllViews();

            Button signInPromptBtn = new Button(this);
            signInPromptBtn.setText("OPEN ADMINISTRATOR SETUP / SIGN IN");
            signInPromptBtn.setBackgroundColor(Color.parseColor("#00F0FF"));
            signInPromptBtn.setTextColor(Color.parseColor("#080B11"));
            signInPromptBtn.setTypeface(null, Typeface.BOLD);
            signInPromptBtn.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
            sessionListContainer.addView(signInPromptBtn);
        }
    }

    private void updateAuthButtonState() {
        if (tokenManager.isLoggedIn()) {
            authBtn.setText("SIGN OUT (" + tokenManager.getUsername() + ")");
            authBtn.setBackgroundColor(Color.parseColor("#1E293B"));
            authBtn.setTextColor(Color.parseColor("#FF0055"));
        } else {
            authBtn.setText("SIGN IN / SETUP");
            authBtn.setBackgroundColor(Color.parseColor("#00F0FF"));
            authBtn.setTextColor(Color.parseColor("#080B11"));
        }
    }

    private void handleAuthAction() {
        if (tokenManager.isLoggedIn()) {
            final String accessToken = tokenManager.getAccessToken();
            final String refreshToken = tokenManager.getRefreshToken();
            final String baseUrl = LoginActivity.getBaseUrl(this);
            tokenManager.clearTokens();
            autoSyncActive = false;
            mainHandler.removeCallbacks(syncRunnable);
            selectedSessionId = null;
            selectedSegmentId = null;
            waveformView.setAmplitudes(new ArrayList<>(), false, -96.0);
            refreshUiForAuthState();
            Toast.makeText(this, "Signed out & tokens revoked", Toast.LENGTH_SHORT).show();

            executor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(baseUrl + "/api/v1/auth/logout");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(8000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    if (accessToken != null) {
                        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    }
                    JSONObject body = new JSONObject();
                    if (refreshToken != null) {
                        body.put("refresh_token", refreshToken);
                    }
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bytes);
                        os.flush();
                    }
                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
    }

    private boolean attemptRefreshTokenSync() {
        String refreshTok = tokenManager.getRefreshToken();
        if (refreshTok == null || refreshTok.isEmpty()) return false;
        String baseUrl = LoginActivity.getBaseUrl(this);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/v1/auth/refresh");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            JSONObject payload = new JSONObject();
            payload.put("refresh_token", refreshTok);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            if (conn.getResponseCode() == 200) {
                JSONObject res = new JSONObject(readStream(conn.getInputStream()));
                String newAccess = res.optString("access_token", "");
                String newRefresh = res.optString("refresh_token", refreshTok);
                if (!newAccess.isEmpty()) {
                    tokenManager.saveTokens(newAccess, newRefresh, tokenManager.getUsername());
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private String httpGetAuthenticated(String urlStr) throws Exception {
        String token = tokenManager.getAccessToken();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code == 401) {
                conn.disconnect();
                conn = null;
                if (attemptRefreshTokenSync()) {
                    return httpGetAuthenticatedRetryOnce(urlStr);
                }
                tokenManager.clearTokens();
                mainHandler.post(this::refreshUiForAuthState);
                throw new Exception("Session expired — please sign in again");
            }
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
            throw new Exception("HTTP " + code);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String httpGetAuthenticatedRetryOnce(String urlStr) throws Exception {
        String token = tokenManager.getAccessToken();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
            throw new Exception("HTTP " + code);
        } finally {
            if (conn != null) conn.disconnect();
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

    private void syncMissedEventsAndRefresh() {
        final String baseUrl = LoginActivity.getBaseUrl(this);
        executor.execute(() -> {
            try {
                String resp = httpGetAuthenticated(baseUrl + "/api/v1/events/missed?since_seq=" + lastSeenEventSeq);
                JSONObject json = new JSONObject(resp);
                int latestSeq = json.optInt("latest_seq", lastSeenEventSeq);
                JSONArray missed = json.optJSONArray("missed_events");
                if (latestSeq > lastSeenEventSeq || (missed != null && missed.length() > 0)) {
                    lastSeenEventSeq = latestSeq;
                    fetchLiveDashboardData(false);
                }
            } catch (Exception ignored) {}
        });
    }

    private void showServerUrlDialog() {
        EditText input = new EditText(this);
        input.setText(LoginActivity.getBaseUrl(this));
        input.setTextColor(Color.WHITE);
        input.setBackgroundColor(Color.parseColor("#0F172A"));
        input.setPadding(24, 20, 24, 20);

        new AlertDialog.Builder(this)
                .setTitle("Telemetry Server URL")
                .setMessage("Enter the HTTPS server URL:")
                .setView(input)
                .setPositiveButton("SAVE & SYNC", (d, w) -> {
                    LoginActivity.setBaseUrl(this, input.getText().toString());
                    if (tokenManager.isLoggedIn()) {
                        fetchLiveDashboardData(true);
                    }
                })
                .setNeutralButton("CLOUD DEFAULT", (d, w) -> {
                    LoginActivity.setBaseUrl(this, LoginActivity.DEFAULT_CLOUD_URL);
                    if (tokenManager.isLoggedIn()) {
                        fetchLiveDashboardData(true);
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void fetchLiveDashboardData(boolean showStatusToast) {
        final String baseUrl = LoginActivity.getBaseUrl(this);
        if (showStatusToast) {
            statusText.setText("Syncing authorized recordings from " + baseUrl + "...");
        }

        executor.execute(() -> {
            try {
                String devRespStr = httpGetAuthenticated(baseUrl + "/api/v1/devices");
                JSONObject devJson = new JSONObject(devRespStr);
                final JSONArray devicesArr = devJson.optJSONArray("devices");
                String devSummary = "Enrolled Devices: 0";
                if (devicesArr != null && devicesArr.length() > 0) {
                    JSONObject firstDev = devicesArr.getJSONObject(0);
                    devSummary = "Enrolled Devices: " + devicesArr.length() + " · Latest: " + firstDev.optString("name", "Android Device");
                }

                String sessRespStr = httpGetAuthenticated(baseUrl + "/api/v1/sessions");
                JSONObject sessJson = new JSONObject(sessRespStr);
                final JSONArray sessions = sessJson.optJSONArray("sessions");
                final String finalDevSummary = devSummary;

                mainHandler.post(() -> {
                    cachedSessions = sessions != null ? sessions : new JSONArray();
                    statusText.setText("LIVE · Authenticated (" + tokenManager.getUsername() + ") · " + baseUrl);
                    deviceSummaryText.setText(finalDevSummary);
                    renderDevicesList(devicesArr);
                    renderSessionsList(cachedSessions);
                });
            } catch (Exception e) {
                mainHandler.post(() -> statusText.setText("Sync notice: " + e.getMessage()));
            }
        });
    }

    private void renderDevicesList(JSONArray devices) {
        deviceListContainer.removeAllViews();
        if (devices == null || devices.length() == 0) return;

        for (int i = 0; i < Math.min(devices.length(), 4); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev == null) continue;
            String name = dev.optString("name", "Android Device");
            String status = dev.optString("status", "online");
            long lastPingSec = dev.optLong("last_ping", 0);
            String timeStr = lastPingSec > 0
                    ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(lastPingSec * 1000L))
                    : "now";

            TextView badge = new TextView(this);
            boolean isRec = "recording".equalsIgnoreCase(status);
            badge.setText((isRec ? "● [RECORDING] " : "● [ONLINE] ") + name + " · Last ping: " + timeStr);
            badge.setTextColor(Color.parseColor(isRec ? "#FF0055" : "#10B981"));
            badge.setTextSize(12);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(0, 2, 0, 2);
            deviceListContainer.addView(badge);
        }
    }

    private void renderSessionsList(JSONArray sessions) {
        sessionListContainer.removeAllViews();
        if (sessions == null || sessions.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("0 authorized recording sessions on server.\nOpen Scream Runner -> tap VOICE + REC (CONSENT) -> stop recording -> review local clips -> authorize sharing.");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            empty.setTextSize(12);
            sessionListContainer.addView(empty);
            segmentListContainer.removeAllViews();
            return;
        }

        int renderedCount = 0;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject sess = sessions.optJSONObject(i);
            if (sess == null) continue;
            final String sessId = sess.optString("id", "");
            String devName = sess.optString("device_name", "Android Device");
            if (!searchQuery.isEmpty()
                    && !sessId.toLowerCase(Locale.US).contains(searchQuery)
                    && !devName.toLowerCase(Locale.US).contains(searchQuery)) {
                continue;
            }

            if (selectedSessionId == null) {
                selectedSessionId = sessId;
            }
            renderedCount++;

            int totalSegs = sess.optInt("total_speech_segments", 0);
            double durSec = sess.optDouble("duration_seconds", 0.0);
            int interruptions = sess.optInt("interruption_count", 0);
            String status = sess.optString("status", "active");
            long startedAt = sess.optLong("started_at", 0);
            String startedStr = startedAt > 0
                    ? new SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(new Date(startedAt * 1000L))
                    : "";

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            boolean isSelected = sessId.equals(selectedSessionId);
            card.setBackgroundColor(Color.parseColor(isSelected ? "#1E293B" : "#151E2E"));
            card.setPadding(24, 18, 24, 18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 6, 0, 6);
            card.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setText((isSelected ? "▶ " : "") + devName + "  [" + status.toUpperCase(Locale.US) + "]  " + startedStr);
            title.setTextColor(Color.parseColor(isSelected ? "#00F0FF" : "#FFFFFF"));
            title.setTextSize(14);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);

            TextView meta = new TextView(this);
            meta.setText(String.format(Locale.US,
                    "Session: %s · Clips: %d · Duration: %.1fs · Interruptions: %d",
                    sessId, totalSegs, durSec, interruptions));
            meta.setTextColor(Color.parseColor(interruptions > 0 ? "#F59E0B" : "#94A3B8"));
            meta.setTextSize(12);
            card.addView(meta);

            card.setOnClickListener(v -> {
                selectedSessionId = sessId;
                selectedSegmentId = null;
                renderSessionsList(cachedSessions);
                fetchSessionSegments(sessId);
            });

            sessionListContainer.addView(card);
        }

        if (renderedCount == 0) {
            TextView noMatch = new TextView(this);
            noMatch.setText("No sessions match search filter \"" + searchQuery + "\".");
            noMatch.setTextColor(Color.parseColor("#94A3B8"));
            sessionListContainer.addView(noMatch);
        } else if (selectedSessionId != null) {
            fetchSessionSegments(selectedSessionId);
        }
    }

    private void fetchSessionSegments(String sessionId) {
        final String baseUrl = LoginActivity.getBaseUrl(this);
        executor.execute(() -> {
            try {
                String respStr = httpGetAuthenticated(baseUrl + "/api/v1/sessions/" + sessionId + "/segments");
                JSONObject json = new JSONObject(respStr);
                final JSONArray segments = json.optJSONArray("segments");
                mainHandler.post(() -> {
                    cachedSegments = segments != null ? segments : new JSONArray();
                    renderSegmentsList(cachedSegments);
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(DashboardActivity.this, "Could not load clips: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderSegmentsList(JSONArray segments) {
        segmentListContainer.removeAllViews();
        if (segments == null || segments.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No speech clips uploaded for this session yet.");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            segmentListContainer.addView(empty);
            return;
        }

        int shown = 0;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            final String segId = seg.optString("id", "");
            final int seqIdx = seg.optInt("seq_index", i + 1);
            final int durationMs = seg.optInt("duration_ms", 1000);
            final double peakDb = seg.optDouble("peak_db", -15.0);
            final double avgDb = seg.optDouble("avg_db", -22.0);
            final long startSec = seg.optLong("start_time", 0);
            final String waveformJson = seg.optString("waveform_data", "[]");
            final boolean isHighlight = peakDb > -10.0;

            if (highlightsOnlyFilter && !isHighlight) {
                continue;
            }
            shown++;

            if (selectedSegmentId == null && shown == 1) {
                selectSegmentForInspection(segId, seqIdx, durationMs, peakDb, waveformJson, false);
            }

            String timeBadge = startSec > 0
                    ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(startSec * 1000L))
                    : "live";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            boolean isSelected = segId.equals(selectedSegmentId);
            row.setBackgroundColor(Color.parseColor(isSelected ? "#1E293B" : "#151E2E"));
            row.setPadding(24, 18, 24, 18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 6, 0, 6);
            row.setLayoutParams(lp);

            TextView title = new TextView(this);
            String badge = isHighlight ? "  [LOUD PEAK > -10dB]" : "  [SPEECH]";
            title.setText("Clip #" + seqIdx + " (" + durationMs + " ms) · " + timeBadge + " · Peak: " + String.format(Locale.US, "%.1f dB", peakDb) + badge);
            title.setTextColor(Color.parseColor(isHighlight ? "#FF0055" : "#00F0FF"));
            title.setTextSize(14);
            title.setTypeface(null, Typeface.BOLD);
            row.addView(title);

            TextView sub = new TextView(this);
            sub.setText("Avg: " + String.format(Locale.US, "%.1f dB", avgDb) + " · ID: " + segId + " · Tap to stream & inspect waveform");
            sub.setTextColor(Color.parseColor("#E2E8F0"));
            sub.setTextSize(12);
            row.addView(sub);

            row.setOnClickListener(v -> {
                selectSegmentForInspection(segId, seqIdx, durationMs, peakDb, waveformJson, true);
                renderSegmentsList(cachedSegments);
            });

            segmentListContainer.addView(row);
        }

        if (shown == 0) {
            TextView none = new TextView(this);
            none.setText("No clips in this session exceed -10 dB peak filter.");
            none.setTextColor(Color.parseColor("#94A3B8"));
            segmentListContainer.addView(none);
        }
    }

    private void selectSegmentForInspection(String segId, int seqIdx, int durationMs, double peakDb, String waveformJson, boolean autoPlay) {
        selectedSegmentId = segId;
        selectedPeakDb = peakDb;
        boolean isHighlight = peakDb > -10.0;

        selectedSegmentTitle.setText(String.format(Locale.US, "INSPECTING AUTHORIZED CLIP #%d (%dms) · PEAK: %.1f dB %s",
                seqIdx, durationMs, peakDb, isHighlight ? "[HIGHLIGHT]" : ""));
        playbackInfoText.setText(String.format(Locale.US, "Ready to stream Clip #%d (%dms) · Speed: %.2fx", seqIdx, durationMs, currentSpeed));

        List<Float> amps = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(waveformJson);
            for (int i = 0; i < arr.length(); i++) {
                amps.add((float) arr.optDouble(i, 0.25));
            }
        } catch (Exception ignored) {}

        waveformView.setAmplitudes(amps, isHighlight, peakDb);

        if (autoPlay) {
            startStreamingSelectedSegment();
        }
    }

    private void togglePlaySelected() {
        if (isPlaying) {
            playerController.pause();
            isPlaying = false;
            playBtn.setText("PLAY SELECTED");
        } else {
            startStreamingSelectedSegment();
        }
    }

    private void startStreamingSelectedSegment() {
        if (!tokenManager.isLoggedIn()) {
            Toast.makeText(this, "Sign in required to play authorized recordings", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        if (selectedSegmentId == null) {
            Toast.makeText(this, "Select an authorized recording clip below first", Toast.LENGTH_SHORT).show();
            return;
        }
        String streamUrl = LoginActivity.getBaseUrl(this) + "/api/v1/audio/" + selectedSegmentId + "/stream";
        playbackInfoText.setText("Streaming authorized audio: " + streamUrl);
        playerController.playUrl(streamUrl, currentSpeed, tokenManager.getAccessToken());
        isPlaying = true;
        playBtn.setText("PAUSE");
    }

    private void downloadSelectedClip() {
        if (!tokenManager.isLoggedIn()) {
            Toast.makeText(this, "Sign in required to export recordings", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedSegmentId == null) {
            Toast.makeText(this, "Select an authorized recording clip first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String downloadUrl = LoginActivity.getBaseUrl(this) + "/api/v1/audio/" + selectedSegmentId + "/download";
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(downloadUrl));
            request.addRequestHeader("Authorization", "Bearer " + tokenManager.getAccessToken());
            request.setTitle("Shared recording " + selectedSegmentId);
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, selectedSegmentId + ".audio");
            ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "Download queued; open the completion notification", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    private void deleteSelectedClip() {
        if (!tokenManager.isLoggedIn()) {
            Toast.makeText(this, "Sign in required to delete recordings", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        if (selectedSegmentId == null) {
            Toast.makeText(this, "Select a recording clip to delete", Toast.LENGTH_SHORT).show();
            return;
        }
        final String segIdToDelete = selectedSegmentId;
        final String baseUrl = LoginActivity.getBaseUrl(this);
        final String token = tokenManager.getAccessToken();
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/v1/audio/" + segIdToDelete);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setConnectTimeout(10000);
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                final int code = conn.getResponseCode();
                mainHandler.post(() -> {
                    if (code >= 200 && code < 300) {
                        Toast.makeText(DashboardActivity.this, "Deleted clip " + segIdToDelete, Toast.LENGTH_SHORT).show();
                        selectedSegmentId = null;
                        fetchLiveDashboardData(false);
                    } else {
                        Toast.makeText(DashboardActivity.this, "Delete failed (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(DashboardActivity.this, "Error deleting: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void cycleSpeed(Button speedBtn) {
        if (currentSpeed == 1.0f) {
            currentSpeed = 1.25f;
        } else if (currentSpeed == 1.25f) {
            currentSpeed = 1.5f;
        } else if (currentSpeed == 1.5f) {
            currentSpeed = 2.0f;
        } else if (currentSpeed == 2.0f) {
            currentSpeed = 0.75f;
        } else {
            currentSpeed = 1.0f;
        }
        speedBtn.setText(String.format(Locale.US, "SPEED: %.2fx", currentSpeed));
        playerController.setSpeed(currentSpeed);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUiForAuthState();
    }

    @Override
    protected void onDestroy() {
        autoSyncActive = false;
        mainHandler.removeCallbacks(syncRunnable);
        playerController.stop();
        super.onDestroy();
    }
}
