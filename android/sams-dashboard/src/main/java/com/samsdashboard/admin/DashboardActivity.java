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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends Activity {

    private TokenManager tokenManager;
    private AudioPlayerController playerController;
    private AudioWaveformView waveformView;
    private TextView statusText;
    private TextView deviceSummaryText;
    private TextView playbackInfoText;
    private TextView selectedSegmentTitle;
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
    private String selectedSessionId = null;
    private String selectedSegmentId = null;
    private double selectedPeakDb = -9.4;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoSyncActive) return;
            fetchLiveDashboardData(false);
            mainHandler.postDelayed(this, 8000);
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
        titleText.setText("SAM'S DASHBOARD");
        titleText.setTextColor(Color.parseColor("#00F0FF"));
        titleText.setTextSize(22);
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
            autoSyncActive = true;
            fetchLiveDashboardData(true);
            mainHandler.removeCallbacks(pollRunnable);
            mainHandler.postDelayed(pollRunnable, 8000);
        });
        actionBar.addView(refreshBtn);

        Button serverBtn = new Button(this);
        serverBtn.setText("SERVER URL");
        serverBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        serverBtn.setTextColor(Color.parseColor("#94A3B8"));
        serverBtn.setOnClickListener(v -> showServerUrlDialog());
        actionBar.addView(serverBtn);

        root.addView(actionBar);

        statusText = new TextView(this);
        statusText.setText("Console Ready · Tap SYNC TELEMETRY or SIGN IN to load cloud sessions");
        statusText.setTextColor(Color.parseColor("#10B981"));
        statusText.setTextSize(13);
        statusText.setPadding(0, 4, 0, 8);
        root.addView(statusText);

        deviceSummaryText = new TextView(this);
        deviceSummaryText.setText("Telemetry Nodes: Local Calibration Ready (Tap SYNC TELEMETRY)");
        deviceSummaryText.setTextColor(Color.parseColor("#94A3B8"));
        deviceSummaryText.setTextSize(12);
        deviceSummaryText.setPadding(0, 0, 0, 10);
        root.addView(deviceSummaryText);

        deviceListContainer = new LinearLayout(this);
        deviceListContainer.setOrientation(LinearLayout.VERTICAL);
        deviceListContainer.setPadding(0, 0, 0, 16);
        root.addView(deviceListContainer);

        LinearLayout playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setBackgroundColor(Color.parseColor("#151E2E"));
        playerCard.setPadding(24, 24, 24, 24);

        selectedSegmentTitle = new TextView(this);
        selectedSegmentTitle.setText("WAVEFORM & ACOUSTIC INSPECTOR (Sample #1 Ready)");
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
        playbackInfoText.setText("Ready to play WAV sample  ·  Speed: 1.0x");
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
        downloadBtn.setText("EXPORT WAV");
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

        TextView sessionHeader = new TextView(this);
        sessionHeader.setText("\nTELEMETRY SESSIONS (TAP TO INSPECT)");
        sessionHeader.setTextColor(Color.parseColor("#94A3B8"));
        sessionHeader.setTextSize(13);
        sessionHeader.setTypeface(null, Typeface.BOLD);
        sessionHeader.setPadding(0, 16, 0, 8);
        root.addView(sessionHeader);

        sessionListContainer = new LinearLayout(this);
        sessionListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionListContainer);

        TextView segmentHeader = new TextView(this);
        segmentHeader.setText("\nAUDIO CLIPS & LOUDNESS PEAKS (> -10 dB)");
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

        loadInitialDemoWaveform();
    }

    private void loadInitialDemoWaveform() {
        List<Float> demoAmps = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            float v = (float) (0.20 + 0.72 * Math.abs(Math.sin(i * 0.24)));
            demoAmps.add(v);
        }
        waveformView.setAmplitudes(demoAmps, true, -8.4);

        TextView hint = new TextView(this);
        hint.setText("Tap SYNC TELEMETRY above to fetch live sessions from the server, or tap PLAY SELECTED to test local waveform audio playback immediately.");
        hint.setTextColor(Color.parseColor("#94A3B8"));
        hint.setTextSize(12);
        sessionListContainer.addView(hint);
    }

    private void updateAuthButtonState() {
        if (tokenManager.isLoggedIn()) {
            authBtn.setText("SIGN OUT (" + tokenManager.getUsername() + ")");
            authBtn.setBackgroundColor(Color.parseColor("#1E293B"));
            authBtn.setTextColor(Color.parseColor("#FF0055"));
        } else {
            authBtn.setText("SIGN IN");
            authBtn.setBackgroundColor(Color.parseColor("#00F0FF"));
            authBtn.setTextColor(Color.parseColor("#080B11"));
        }
    }

    private void handleAuthAction() {
        if (tokenManager.isLoggedIn()) {
            final String token = tokenManager.getAccessToken();
            final String baseUrl = LoginActivity.getBaseUrl(this);
            tokenManager.clearTokens();
            updateAuthButtonState();
            Toast.makeText(this, "Signed out & token revoked", Toast.LENGTH_SHORT).show();

            executor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(baseUrl + "/api/v1/auth/logout");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(8000);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
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
                    fetchLiveDashboardData(true);
                })
                .setNeutralButton("CLOUD DEFAULT", (d, w) -> {
                    LoginActivity.setBaseUrl(this, LoginActivity.DEFAULT_CLOUD_URL);
                    fetchLiveDashboardData(true);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private String httpGet(String urlStr, String token) throws Exception {
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
            if (code == 401 && token != null) {
                tokenManager.clearTokens();
                mainHandler.post(this::updateAuthButtonState);
                conn.disconnect();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                code = conn.getResponseCode();
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

    private void fetchLiveDashboardData(boolean showStatusToast) {
        final String baseUrl = LoginActivity.getBaseUrl(this);
        final String token = tokenManager.getAccessToken();
        if (showStatusToast) {
            statusText.setText("Syncing telemetry from " + baseUrl + "...");
        }

        executor.execute(() -> {
            try {
                String devRespStr = httpGet(baseUrl + "/api/v1/devices", token);
                JSONObject devJson = new JSONObject(devRespStr);
                final JSONArray devicesArr = devJson.optJSONArray("devices");
                String devSummary = "Active Telemetry Nodes: 0";
                if (devicesArr != null && devicesArr.length() > 0) {
                    JSONObject firstDev = devicesArr.getJSONObject(0);
                    devSummary = "Active Nodes: " + devicesArr.length() + " · Latest: " + firstDev.optString("name", "Android Node");
                }

                String sessRespStr = httpGet(baseUrl + "/api/v1/sessions", token);
                JSONObject sessJson = new JSONObject(sessRespStr);
                final JSONArray sessions = sessJson.optJSONArray("sessions");
                final String finalDevSummary = devSummary;

                mainHandler.post(() -> {
                    statusText.setText("Connected · Synced with " + baseUrl);
                    deviceSummaryText.setText(finalDevSummary);
                    renderDevicesList(devicesArr);
                    renderSessionsList(sessions);
                });
            } catch (Exception e) {
                mainHandler.post(() -> statusText.setText("Offline / Local Mode (" + e.getMessage() + ")"));
            }
        });
    }

    private void renderDevicesList(JSONArray devices) {
        deviceListContainer.removeAllViews();
        if (devices == null || devices.length() == 0) return;

        for (int i = 0; i < Math.min(devices.length(), 4); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev == null) continue;
            String name = dev.optString("name", "Android Node");
            String status = dev.optString("status", "online");
            String osVer = dev.optString("os_version", "Android");

            TextView badge = new TextView(this);
            boolean isRec = "recording".equalsIgnoreCase(status);
            badge.setText((isRec ? "● [ACTIVE SESSION] " : "● [READY] ") + name + " (" + osVer + ")");
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
            empty.setText("No telemetry sessions found on server.");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            sessionListContainer.addView(empty);
            return;
        }

        if (selectedSessionId == null) {
            JSONObject first = sessions.optJSONObject(0);
            if (first != null) {
                selectedSessionId = first.optString("id", null);
            }
        }

        for (int i = 0; i < sessions.length(); i++) {
            JSONObject sess = sessions.optJSONObject(i);
            if (sess == null) continue;
            final String sessId = sess.optString("id", "");
            String devName = sess.optString("device_name", "Android Node");
            int totalSegs = sess.optInt("total_speech_segments", 0);
            String status = sess.optString("status", "active");

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
            title.setText((isSelected ? "▶ " : "") + devName + "  [" + status.toUpperCase(Locale.US) + "]");
            title.setTextColor(Color.parseColor(isSelected ? "#00F0FF" : "#FFFFFF"));
            title.setTextSize(14);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);

            TextView meta = new TextView(this);
            meta.setText("Session ID: " + sessId + "  ·  Clips: " + totalSegs);
            meta.setTextColor(Color.parseColor("#94A3B8"));
            meta.setTextSize(12);
            card.addView(meta);

            card.setOnClickListener(v -> {
                selectedSessionId = sessId;
                selectedSegmentId = null;
                renderSessionsList(sessions);
                fetchSessionSegments(sessId);
            });

            sessionListContainer.addView(card);
        }

        if (selectedSessionId != null) {
            fetchSessionSegments(selectedSessionId);
        }
    }

    private void fetchSessionSegments(String sessionId) {
        final String baseUrl = LoginActivity.getBaseUrl(this);
        final String token = tokenManager.getAccessToken();
        executor.execute(() -> {
            try {
                String respStr = httpGet(baseUrl + "/api/v1/sessions/" + sessionId + "/segments", token);
                JSONObject json = new JSONObject(respStr);
                final JSONArray segments = json.optJSONArray("segments");
                mainHandler.post(() -> renderSegmentsList(segments));
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(DashboardActivity.this, "Could not load clips: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderSegmentsList(JSONArray segments) {
        segmentListContainer.removeAllViews();
        if (segments == null || segments.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No audio clips uploaded for this session yet.");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            segmentListContainer.addView(empty);
            return;
        }

        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            final String segId = seg.optString("id", "");
            final int seqIdx = seg.optInt("seq_index", i + 1);
            final int durationMs = seg.optInt("duration_ms", 1000);
            final double peakDb = seg.optDouble("peak_db", -15.0);
            final double avgDb = seg.optDouble("avg_db", -22.0);
            final String waveformJson = seg.optString("waveform_data", "[]");
            final boolean isHighlight = peakDb > -10.0;

            if (selectedSegmentId == null && i == 0) {
                selectSegmentForInspection(segId, seqIdx, durationMs, peakDb, waveformJson, false);
            }

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
            String badge = isHighlight ? "  [PEAK > -10dB]" : "  [SAMPLE]";
            title.setText("Clip #" + seqIdx + " (" + durationMs + " ms) · Peak: " + String.format(Locale.US, "%.1f dB", peakDb) + badge);
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
                renderSegmentsList(segments);
            });

            segmentListContainer.addView(row);
        }
    }

    private void selectSegmentForInspection(String segId, int seqIdx, int durationMs, double peakDb, String waveformJson, boolean autoPlay) {
        selectedSegmentId = segId;
        selectedPeakDb = peakDb;
        boolean isHighlight = peakDb > -10.0;

        selectedSegmentTitle.setText(String.format(Locale.US, "INSPECTING CLIP #%d (%dms) · PEAK: %.1f dB %s",
                seqIdx, durationMs, peakDb, isHighlight ? "[HIGHLIGHT]" : ""));

        List<Float> amps = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(waveformJson);
            for (int i = 0; i < arr.length(); i++) {
                amps.add((float) arr.optDouble(i, 0.25));
            }
        } catch (Exception ignored) {}

        if (amps.isEmpty()) {
            for (int i = 0; i < 50; i++) {
                amps.add((float) (0.25 + 0.65 * Math.abs(Math.sin(i * 0.22))));
            }
        }
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
        if (selectedSegmentId == null) {
            playbackInfoText.setText("Playing built-in local calibration WAV sample (" + currentSpeed + "x)");
            playerController.playLocalDemoTone(currentSpeed);
            isPlaying = true;
            playBtn.setText("PAUSE");
            return;
        }
        String streamUrl = LoginActivity.getBaseUrl(this) + "/api/v1/audio/" + selectedSegmentId + "/stream";
        playbackInfoText.setText("Streaming WAV audio: " + streamUrl);
        playerController.playUrl(streamUrl, currentSpeed, tokenManager.getAccessToken());
        isPlaying = true;
        playBtn.setText("PAUSE");
    }

    private void downloadSelectedClip() {
        if (selectedSegmentId == null) {
            Toast.makeText(this, "Tap SYNC TELEMETRY and select a remote clip first", Toast.LENGTH_SHORT).show();
            return;
        }
        String downloadUrl = LoginActivity.getBaseUrl(this) + "/api/v1/audio/" + selectedSegmentId + "/download";
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
        startActivity(browserIntent);
    }

    private void deleteSelectedClip() {
        if (selectedSegmentId == null) {
            Toast.makeText(this, "Select a synced clip to delete", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(DashboardActivity.this, "Delete requires Sign In (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
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
        if (authBtn != null) {
            updateAuthButtonState();
        }
        if (tokenManager.isLoggedIn()) {
            fetchLiveDashboardData(false);
        }
    }

    @Override
    protected void onDestroy() {
        autoSyncActive = false;
        mainHandler.removeCallbacks(pollRunnable);
        playerController.stop();
        super.onDestroy();
    }
}
