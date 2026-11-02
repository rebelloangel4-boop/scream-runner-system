package com.samsdashboard.admin;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.samsdashboard.admin.auth.TokenManager;
import com.samsdashboard.admin.player.AudioPlayerController;
import com.samsdashboard.admin.ui.AudioWaveformView;

import java.util.ArrayList;
import java.util.List;

public class DashboardActivity extends Activity {

    private TokenManager tokenManager;
    private AudioPlayerController playerController;
    private AudioWaveformView waveformView;
    private TextView statusText;
    private TextView playbackInfoText;
    private LinearLayout sessionListContainer;

    private float currentSpeed = 1.0f;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tokenManager = new TokenManager(this);
        playerController = new AudioPlayerController();

        // Dark theme layout
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#080B11"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 48);

        // Header Title
        TextView titleText = new TextView(this);
        titleText.setText("SAM'S DASHBOARD");
        titleText.setTextColor(Color.parseColor("#00F0FF"));
        titleText.setTextSize(24);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleText);

        statusText = new TextView(this);
        statusText.setText("CONNECTED  ·  Pixel 7 Pro (Active Recording)  ·  WebSocket Sync Live");
        statusText.setTextColor(Color.parseColor("#10B981"));
        statusText.setTextSize(14);
        statusText.setPadding(0, 8, 0, 24);
        root.addView(statusText);

        // Player section Card
        LinearLayout playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setBackgroundColor(Color.parseColor("#151E2E"));
        playerCard.setPadding(24, 24, 24, 24);

        TextView playerHeader = new TextView(this);
        playerHeader.setText("AUDIO INSPECTION & WAVEFORM");
        playerHeader.setTextColor(Color.parseColor("#94A3B8"));
        playerHeader.setTextSize(12);
        playerCard.addView(playerHeader);

        waveformView = new AudioWaveformView(this);
        waveformView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
        ));
        waveformView.setOnScrubListener(progress -> playerController.seekTo(progress));
        playerCard.addView(waveformView);

        playbackInfoText = new TextView(this);
        playbackInfoText.setText("Ready to stream  ·  Speed: 1.0x  ·  HTTP 206 Partial Range");
        playbackInfoText.setTextColor(Color.parseColor("#E2E8F0"));
        playbackInfoText.setTextSize(13);
        playbackInfoText.setPadding(0, 12, 0, 16);
        playerCard.addView(playbackInfoText);

        // Player controls bar
        LinearLayout playerControls = new LinearLayout(this);
        playerControls.setOrientation(LinearLayout.HORIZONTAL);

        Button playBtn = new Button(this);
        playBtn.setText("PLAY DEMO CLIP");
        playBtn.setBackgroundColor(Color.parseColor("#00F0FF"));
        playBtn.setTextColor(Color.parseColor("#080B11"));
        playBtn.setOnClickListener(v -> togglePlayDemo());
        playerControls.addView(playBtn);

        Button speedBtn = new Button(this);
        speedBtn.setText("SPEED: 1.0x");
        speedBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        speedBtn.setTextColor(Color.parseColor("#00F0FF"));
        speedBtn.setOnClickListener(v -> cycleSpeed(speedBtn));
        playerControls.addView(speedBtn);

        Button downloadBtn = new Button(this);
        downloadBtn.setText("DOWNLOAD CLIP");
        downloadBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        downloadBtn.setTextColor(Color.parseColor("#10B981"));
        downloadBtn.setOnClickListener(v -> Toast.makeText(this, "Downloading audio segment to local storage...", Toast.LENGTH_SHORT).show());
        playerControls.addView(downloadBtn);

        playerCard.addView(playerControls);
        root.addView(playerCard);

        // Speech sessions section
        TextView sessionHeader = new TextView(this);
        sessionHeader.setText("\nRECORDING SESSIONS & SPEECH HIGHLIGHTS");
        sessionHeader.setTextColor(Color.parseColor("#94A3B8"));
        sessionHeader.setTextSize(14);
        sessionHeader.setPadding(0, 16, 0, 8);
        root.addView(sessionHeader);

        sessionListContainer = new LinearLayout(this);
        sessionListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionListContainer);

        populateDemoSessions();

        scrollView.addView(root);
        setContentView(scrollView);

        // Setup player callbacks
        playerController.setCallback(new AudioPlayerController.PlayerCallback() {
            @Override
            public void onProgress(int currentMs, int durationMs, float progressRatio) {
                waveformView.setProgress(progressRatio);
                playbackInfoText.setText(String.format("Streaming: %dms / %dms (%.1fx)", currentMs, durationMs, currentSpeed));
            }

            @Override
            public void onPlaybackCompleted() {
                isPlaying = false;
                waveformView.setProgress(0.0f);
                playbackInfoText.setText("Playback completed  ·  Speed: " + currentSpeed + "x");
            }

            @Override
            public void onError(String message) {
                Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void togglePlayDemo() {
        if (isPlaying) {
            playerController.pause();
            isPlaying = false;
        } else {
            // Stream demo audio from backend
            String streamUrl = "http://10.0.2.2:8000/api/v1/audio/seg_demo_3/stream";
            playerController.playUrl(streamUrl, currentSpeed);
            isPlaying = true;
            // Load waveform
            List<Float> demoAmps = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                demoAmps.add((float) (0.2 + 0.7 * Math.sin(i * 0.15)));
            }
            waveformView.setAmplitudes(demoAmps, true, -5.2);
        }
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
        speedBtn.setText(String.format("SPEED: %.2fx", currentSpeed));
        playerController.setSpeed(currentSpeed);
    }

    private void populateDemoSessions() {
        String[][] demoClips = {
                {"Segment #1", "00:02:14", "3,200 ms", "-8.4 dB", "HIGH PEAK HIGHLIGHT", "#FF0055"},
                {"Segment #2", "00:07:30", "2,400 ms", "-14.1 dB", "Speech Detected", "#00F0FF"},
                {"Segment #3", "00:14:50", "4,100 ms", "-5.2 dB", "LOUD MOMENT (> -10dB)", "#FF0055"},
                {"Segment #4", "00:23:40", "1,800 ms", "-19.3 dB", "Whisper Command", "#10B981"}
        };

        for (String[] clip : demoClips) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.parseColor("#151E2E"));
            row.setPadding(20, 16, 20, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 8, 0, 8);
            row.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(clip[0] + "  ·  " + clip[1] + "  ·  " + clip[2]);
            name.setTextColor(Color.parseColor("#E2E8F0"));
            name.setTextSize(13);
            row.addView(name);

            TextView tag = new TextView(this);
            tag.setText("   " + clip[3] + " [" + clip[4] + "]");
            tag.setTextColor(Color.parseColor(clip[5]));
            tag.setTextSize(12);
            row.addView(tag);

            sessionListContainer.addView(row);
        }
    }

    @Override
    protected void onDestroy() {
        playerController.stop();
        super.onDestroy();
    }
}
