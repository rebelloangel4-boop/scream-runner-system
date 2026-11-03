package com.screamrunner.game;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.screamrunner.game.audio.CoordinatedAudioManager;
import com.screamrunner.game.audio.SmartRecordingService;
import com.screamrunner.game.upload.AudioUploadWorker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity v3.0 (Scream Runner):
 * - Delta-time 60 FPS SurfaceView game engine with real-time frame-time (ms) & FPS telemetry.
 * - Zero UI-thread flooding from the render loop (all HUD metrics drawn directly on Canvas).
 * - Synthesized AudioTrack sound effects (jump, double jump, coin, shield, hit) with MUTE control.
 * - Voice-loudness jump with adjustable sensitivity + adaptive noise-floor calibration.
 * - Explicit consent-gated Android 14–16 foreground microphone recording with 1-tap STOP,
 *   local clip retention/deletion, and OEM battery-optimization guidance.
 */
public class MainActivity extends Activity implements CoordinatedAudioManager.AudioFrameListener, AudioUploadWorker.UploadStatusListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final String PREF_GAME = "scream_runner_game_state_v3";
    private static final String KEY_HIGH_SCORE = "high_score";
    private static final String KEY_SOUND_MUTED = "sound_muted";
    private static final String KEY_KEEP_BG_REC = "keep_bg_recording";

    private GameView gameView;
    private TextView micDbText;
    private TextView uploadStatusText;
    private Button recordToggleButton;
    private Button pauseButton;
    private Button muteButton;

    private volatile boolean isRecordingActive = false;
    private volatile boolean userExplicitlyConsented = false;
    private volatile boolean keepRecordingWhenBackgrounded = true;
    private volatile boolean soundMuted = false;
    private String currentSessionId = null;
    private volatile double currentMicDb = -96.0;
    private volatile double jumpThresholdDb = -26.0;
    private volatile int uploadedSegmentsCount = 0;
    private volatile int highScore = 0;

    private final ExecutorService sfxExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREF_GAME, Context.MODE_PRIVATE);
        highScore = prefs.getInt(KEY_HIGH_SCORE, 0);
        soundMuted = prefs.getBoolean(KEY_SOUND_MUTED, false);
        keepRecordingWhenBackgrounded = prefs.getBoolean(KEY_KEEP_BG_REC, true);

        AudioUploadWorker.setStatusListener(this);

        FrameLayout rootLayout = new FrameLayout(this);
        gameView = new GameView(this);
        rootLayout.addView(gameView);

        // Compact Top Controls Bar (Never blocks gameplay area)
        LinearLayout hudLayout = new LinearLayout(this);
        hudLayout.setOrientation(LinearLayout.VERTICAL);
        hudLayout.setPadding(24, 12, 24, 8);

        LinearLayout topStatusRow = new LinearLayout(this);
        topStatusRow.setOrientation(LinearLayout.HORIZONTAL);

        micDbText = new TextView(this);
        micDbText.setText("MIC: OFF (TOUCH MODE)");
        micDbText.setTextColor(Color.parseColor("#94A3B8"));
        micDbText.setTextSize(12);
        micDbText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        topStatusRow.addView(micDbText);

        uploadStatusText = new TextView(this);
        uploadStatusText.setText("   [CONSENT REQUIRED FOR MIC]");
        uploadStatusText.setTextColor(Color.parseColor("#10B981"));
        uploadStatusText.setTextSize(12);
        uploadStatusText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        topStatusRow.addView(uploadStatusText);

        hudLayout.addView(topStatusRow);

        LinearLayout controlsBar = new LinearLayout(this);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setPadding(0, 6, 0, 0);

        recordToggleButton = new Button(this);
        recordToggleButton.setText("VOICE + REC (CONSENT)");
        recordToggleButton.setBackgroundColor(Color.parseColor("#151E2E"));
        recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        recordToggleButton.setTextSize(10);
        recordToggleButton.setOnClickListener(v -> toggleAuthorizedRecording());
        controlsBar.addView(recordToggleButton);

        Button jumpButton = new Button(this);
        jumpButton.setText("JUMP / 2X JUMP");
        jumpButton.setBackgroundColor(Color.parseColor("#151E2E"));
        jumpButton.setTextColor(Color.parseColor("#10B981"));
        jumpButton.setTextSize(10);
        jumpButton.setOnClickListener(v -> gameView.handleActionTap(1.0));
        controlsBar.addView(jumpButton);

        pauseButton = new Button(this);
        pauseButton.setText("PAUSE");
        pauseButton.setBackgroundColor(Color.parseColor("#1E293B"));
        pauseButton.setTextColor(Color.parseColor("#E2E8F0"));
        pauseButton.setTextSize(10);
        pauseButton.setOnClickListener(v -> gameView.togglePause());
        controlsBar.addView(pauseButton);

        muteButton = new Button(this);
        muteButton.setText(soundMuted ? "SFX: MUTED" : "SFX: ON");
        muteButton.setBackgroundColor(Color.parseColor("#1E293B"));
        muteButton.setTextColor(Color.parseColor(soundMuted ? "#F59E0B" : "#00F0FF"));
        muteButton.setTextSize(10);
        muteButton.setOnClickListener(v -> toggleMute());
        controlsBar.addView(muteButton);

        Button localClipsBtn = new Button(this);
        localClipsBtn.setText("LOCAL CLIPS");
        localClipsBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        localClipsBtn.setTextColor(Color.parseColor("#F59E0B"));
        localClipsBtn.setTextSize(10);
        localClipsBtn.setOnClickListener(v -> showLocalStorageDialog());
        controlsBar.addView(localClipsBtn);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("SETTINGS & BATTERY");
        settingsBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        settingsBtn.setTextColor(Color.parseColor("#94A3B8"));
        settingsBtn.setTextSize(10);
        settingsBtn.setOnClickListener(v -> showSettingsAndBatteryDialog());
        controlsBar.addView(settingsBtn);

        hudLayout.addView(controlsBar);
        rootLayout.addView(hudLayout);

        setContentView(rootLayout);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && SmartRecordingService.ACTION_STOP_FROM_NOTIFICATION.equals(intent.getAction())) {
            stopActiveConsentSession("Stopped via notification STOP control");
        }
    }

    private void toggleMute() {
        soundMuted = !soundMuted;
        getSharedPreferences(PREF_GAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SOUND_MUTED, soundMuted)
                .apply();
        muteButton.setText(soundMuted ? "SFX: MUTED" : "SFX: ON");
        muteButton.setTextColor(Color.parseColor(soundMuted ? "#F59E0B" : "#00F0FF"));
    }

    private void playSynthesizedSfx(final int freqStart, final int freqEnd, final int durationMs) {
        if (soundMuted) return;
        sfxExecutor.execute(() -> {
            AudioTrack track = null;
            try {
                int sampleRate = 16000;
                int numSamples = Math.max(160, (sampleRate * durationMs) / 1000);
                short[] pcm = new short[numSamples];
                for (int i = 0; i < numSamples; i++) {
                    double progress = (double) i / numSamples;
                    double freq = freqStart + (freqEnd - freqStart) * progress;
                    double env = 1.0 - progress;
                    pcm[i] = (short) (Math.sin(2.0 * Math.PI * i * freq / sampleRate) * 10000 * env);
                }
                int bufBytes = numSamples * 2;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track = new AudioTrack(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(),
                            new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            Math.max(bufBytes, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)),
                            AudioTrack.MODE_STATIC,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                    );
                } else {
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(bufBytes, 2048), AudioTrack.MODE_STATIC);
                }
                track.write(pcm, 0, numSamples);
                track.play();
                Thread.sleep(durationMs + 20L);
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try {
                        track.release();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void showLocalStorageDialog() {
        SmartRecordingService svc = SmartRecordingService.getInstance(this);
        List<File> clips = svc.listLocalClips();
        int pendingCount = AudioUploadWorker.getPendingQueueSize(this);
        long totalBytes = 0;
        StringBuilder details = new StringBuilder();
        details.append("Retention Policy: Auto-delete local clips older than ").append(svc.getRetentionDays()).append(" days\n");
        details.append("Pending Offline Upload Queue: ").append(pendingCount).append(" clips\n\n");

        for (int i = 0; i < clips.size(); i++) {
            File f = clips.get(i);
            totalBytes += f.length();
            if (i < 8) {
                details.append("• ").append(f.getName()).append(" (").append(Math.max(1, f.length() / 1024)).append(" KB)\n");
            }
        }
        if (clips.isEmpty()) {
            details.append("No local M4A/WAV clips currently stored in app-private sandbox.");
        }

        new AlertDialog.Builder(this)
                .setTitle("App-Private Audio Storage (" + clips.size() + " clips · " + (totalBytes / 1024) + " KB)")
                .setMessage(details.toString())
                .setPositiveButton("RETRY OFFLINE QUEUE", (d, w) -> {
                    AudioUploadWorker.flushPendingQueue(this);
                    Toast.makeText(this, "Flushing offline upload queue...", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("WIPE ALL LOCAL CLIPS", (d, w) -> {
                    int count = svc.deleteAllLocalClips();
                    Toast.makeText(this, "Deleted " + count + " local audio clips", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void showSettingsAndBatteryDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 24, 36, 16);
        layout.setBackgroundColor(Color.parseColor("#0F172A"));

        TextView sensLabel = new TextView(this);
        sensLabel.setText(String.format(Locale.US, "Voice Jump & VAD Sensitivity Gate: %.0f dB", jumpThresholdDb));
        sensLabel.setTextColor(Color.parseColor("#00F0FF"));
        sensLabel.setTextSize(12);
        layout.addView(sensLabel);

        SeekBar sensBar = new SeekBar(this);
        sensBar.setMax(45);
        sensBar.setProgress((int) (jumpThresholdDb + 55));
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                jumpThresholdDb = -55 + progress;
                sensLabel.setText(String.format(Locale.US, "Voice Jump & VAD Sensitivity Gate: %.0f dB", jumpThresholdDb));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(sensBar);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("\nTelemetry HTTPS Server Endpoint:");
        urlLabel.setTextColor(Color.parseColor("#94A3B8"));
        urlLabel.setTextSize(12);
        layout.addView(urlLabel);

        EditText urlInput = new EditText(this);
        urlInput.setText(AudioUploadWorker.getBaseUrl(this));
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(12);
        layout.addView(urlInput);

        TextView batteryInfo = new TextView(this);
        batteryInfo.setText("\nOEM Battery & Background Recording Guidance (Android 14–16):\n" +
                "• Samsung OneUI: Settings -> Apps -> Scream Runner -> Battery -> Unrestricted.\n" +
                "• Xiaomi MIUI/HyperOS: App Info -> Battery Saver -> No restrictions.\n" +
                "• OnePlus/Oppo: App Info -> Battery Usage -> Allow background activity.\n" +
                "Note: Android's green microphone indicator and persistent STOP notification always remain visible while active.");
        batteryInfo.setTextColor(Color.parseColor("#CBD5E1"));
        batteryInfo.setTextSize(11);
        layout.addView(batteryInfo);

        new AlertDialog.Builder(this)
                .setTitle("Scream Runner v3.0 Settings")
                .setView(layout)
                .setPositiveButton("SAVE SETTINGS", (d, w) -> {
                    AudioUploadWorker.setBaseUrl(this, urlInput.getText().toString());
                    SmartRecordingService.getInstance(this).getVadEngine().triggerCalibration();
                    Toast.makeText(this, "Settings saved & noise floor calibrated", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("ANDROID BATTERY SETTINGS", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Open Android Settings -> Apps -> Scream Runner -> Battery", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void requestMicPermissionAndActivate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), PERMISSION_REQ_CODE);
                return;
            }
        }
        activateConsentedSession();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (audioGranted && userExplicitlyConsented) {
                activateConsentedSession();
            } else {
                userExplicitlyConsented = false;
                Toast.makeText(this, "Microphone permission declined; continuing in Touch Jump mode", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void activateConsentedSession() {
        currentSessionId = "sess_android_" + (System.currentTimeMillis() / 1000);
        CoordinatedAudioManager.getInstance().registerGameplayListener(this);
        SmartRecordingService.getInstance(this).startSession(currentSessionId);
        isRecordingActive = true;
        recordToggleButton.setText("● STOP REC");
        recordToggleButton.setBackgroundColor(Color.parseColor("#3B0D1E"));
        recordToggleButton.setTextColor(Color.parseColor("#FF0055"));
        micDbText.setTextColor(Color.parseColor("#FF0055"));
        uploadStatusText.setText("   [300ms PRE-ROLL VAD ACTIVE · TAP STOP TO END]");
    }

    private void stopActiveConsentSession(String reason) {
        SmartRecordingService.getInstance(this).stopSession();
        CoordinatedAudioManager.getInstance().unregisterGameplayListener(this);
        CoordinatedAudioManager.getInstance().stopCapture();
        isRecordingActive = false;
        userExplicitlyConsented = false;
        recordToggleButton.setText("VOICE + REC (CONSENT)");
        recordToggleButton.setBackgroundColor(Color.parseColor("#151E2E"));
        recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        micDbText.setText("MIC: OFF (TOUCH MODE)");
        micDbText.setTextColor(Color.parseColor("#94A3B8"));
        uploadStatusText.setText("   [MIC STOPPED & RELEASED]");
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
        }
    }

    private long lastUiMicUpdateMs = 0;

    @Override
    public void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        if (!isRecordingActive || !userExplicitlyConsented) return;
        currentMicDb = measuredDb;

        long now = System.currentTimeMillis();
        if (now - lastUiMicUpdateMs > 120) {
            lastUiMicUpdateMs = now;
            final double noiseFloor = SmartRecordingService.getInstance(this).getVadEngine().getNoiseFloorDb();
            runOnUiThread(() -> {
                if (micDbText != null && isRecordingActive) {
                    micDbText.setText(String.format(Locale.US, "MIC: %.1f dB (FLOOR: %.0f dB | GATE: %.0f dB)",
                            currentMicDb, noiseFloor, jumpThresholdDb));
                }
            });
        }

        if (measuredDb > jumpThresholdDb) {
            double intensity = Math.min(1.0, Math.max(0.25, (measuredDb - jumpThresholdDb) / 20.0));
            gameView.triggerVoiceJump(intensity);
        }
    }

    @Override
    public void onSessionRegistered(String sessionId, String deviceName) {
        runOnUiThread(() -> {
            if (uploadStatusText != null && isRecordingActive) {
                uploadStatusText.setText("   [ENROLLED · " + sessionId + "]");
            }
        });
    }

    @Override
    public void onSegmentUploaded(int seqIndex, String segmentId, double peakDb) {
        uploadedSegmentsCount++;
        runOnUiThread(() -> {
            if (uploadStatusText != null) {
                uploadStatusText.setText(String.format(Locale.US, "   [SYNCED #%d · %.1f dB]", uploadedSegmentsCount, peakDb));
            }
        });
    }

    @Override
    public void onUploadError(String error) {
        runOnUiThread(() -> {
            if (uploadStatusText != null) {
                uploadStatusText.setText("   [" + error + "]");
            }
        });
    }

    private void toggleAuthorizedRecording() {
        if (isRecordingActive) {
            stopActiveConsentSession("Microphone stopped & hardware released");
        } else {
            showConsentDisclosureDialog();
        }
    }

    private void showConsentDisclosureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Explicit Consent: Voice Jump & Audio Recording (v3.0)")
                .setMessage("Scream Runner requests your explicit authorization to enable microphone access:\n\n" +
                        "1. Voice Jump & Adaptive VAD: Uses a 300ms pre-roll and 500ms post-roll buffer with automatic noise-floor calibration.\n" +
                        "2. Android 14–16 Foreground Compliance: Displays a persistent system notification with a 1-tap STOP RECORDING button while active.\n" +
                        "3. Background & Screen-Off Choice: Choose below whether recording should continue via the foreground notification if you switch apps, or stop immediately on pause.\n" +
                        "4. Encrypted HTTPS Sync: Clips are encoded to AAC/M4A (or lossless WAV), verified with SHA-256, and uploaded to " + AudioUploadWorker.getBaseUrl(this) + ".")
                .setPositiveButton("CONSENT (FOREGROUND + BACKGROUND)", (dialog, which) -> {
                    userExplicitlyConsented = true;
                    keepRecordingWhenBackgrounded = true;
                    SmartRecordingService.getInstance(this).setSyncUploadEnabled(true);
                    requestMicPermissionAndActivate();
                })
                .setNeutralButton("CONSENT (IN-APP ONLY)", (dialog, which) -> {
                    userExplicitlyConsented = true;
                    keepRecordingWhenBackgrounded = false;
                    SmartRecordingService.getInstance(this).setSyncUploadEnabled(true);
                    requestMicPermissionAndActivate();
                })
                .setNegativeButton("DECLINE (TOUCH ONLY)", (dialog, which) -> {
                    userExplicitlyConsented = false;
                })
                .show();
    }

    private void saveHighScore(int newBest) {
        highScore = newBest;
        getSharedPreferences(PREF_GAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_HIGH_SCORE, highScore)
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resumeLoop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pauseLoop();
        if (isRecordingActive && !keepRecordingWhenBackgrounded) {
            stopActiveConsentSession(null);
        }
    }

    @Override
    protected void onDestroy() {
        if (isRecordingActive) {
            stopActiveConsentSession(null);
        }
        sfxExecutor.shutdownNow();
        super.onDestroy();
    }

    // ----------------- Delta-Time 60 FPS 2D Endless Runner Engine -----------------
    private class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
        private Thread gameThread;
        private volatile boolean isRunningLoop = false;
        private volatile boolean inTitleMenu = true;
        private volatile boolean isPaused = false;
        private volatile boolean isGameOver = false;

        private final SurfaceHolder holder;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float playerX = 160f;
        private float playerY = 400f;
        private float playerVelocityY = 0f;
        private final float gravityPxPerSec2 = 1650f;
        private float groundY = 500f;
        private int jumpsRemaining = 2;
        private long lastVoiceJumpMs = 0;

        private float scoreFloat = 0f;
        private int score = 0;
        private int coins = 0;
        private int shields = 3;
        private int combo = 1;
        private int level = 1;
        private long invulnerableUntil = 0;

        // Frame-time & FPS telemetry
        private float currentFps = 60.0f;
        private float avgFrameTimeMs = 4.2f;

        private static final int OBS_GROUND_SPIKE = 0;
        private static final int OBS_AERIAL_DRONE = 1;
        private static final int OBS_LASER_TOWER = 2;

        private class Obstacle {
            RectF rect;
            int type;
            Obstacle(RectF r, int type) { this.rect = r; this.type = type; }
        }

        private class Particle {
            float x, y, vx, vy, alpha;
            int color;
            Particle(float x, float y, float vx, float vy, int color) {
                this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.alpha = 1.0f; this.color = color;
            }
        }

        private final List<Obstacle> obstacles = new ArrayList<>();
        private final List<RectF> coinItems = new ArrayList<>();
        private final List<RectF> shieldItems = new ArrayList<>();
        private final List<Particle> particles = new ArrayList<>();
        private final Random random = new Random();
        private float spawnTimerSec = 0f;
        private float gridOffset = 0f;
        private float starOffset = 0f;

        public GameView(Context context) {
            super(context);
            holder = getHolder();
            holder.addCallback(this);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        public synchronized void handleActionTap(double intensity) {
            if (inTitleMenu) {
                inTitleMenu = false;
                resetGame();
                playSynthesizedSfx(440, 660, 70);
                return;
            }
            if (isGameOver) {
                resetGame();
                playSynthesizedSfx(440, 660, 70);
                return;
            }
            if (isPaused) {
                togglePause();
                return;
            }
            if (jumpsRemaining > 0) {
                boolean isFirstJump = (jumpsRemaining == 2);
                playerVelocityY = (float) (-620.0 - (240.0 * intensity));
                jumpsRemaining--;
                spawnParticles(playerX + 20, playerY + 48, Color.parseColor("#00F0FF"), 10);
                playSynthesizedSfx(isFirstJump ? 380 : 520, isFirstJump ? 620 : 840, 65);
            }
        }

        public synchronized void triggerVoiceJump(double intensity) {
            if (inTitleMenu || isGameOver || isPaused) return;
            long now = System.currentTimeMillis();
            if (now - lastVoiceJumpMs < 220) return;
            if (playerY >= groundY - 18 || jumpsRemaining == 1) {
                lastVoiceJumpMs = now;
                playerVelocityY = (float) (-640.0 - (260.0 * intensity));
                jumpsRemaining = Math.max(0, jumpsRemaining - 1);
                spawnParticles(playerX + 20, playerY + 48, Color.parseColor("#FF0055"), 8);
                playSynthesizedSfx(420, 740, 60);
            }
        }

        public synchronized void togglePause() {
            if (inTitleMenu || isGameOver) return;
            isPaused = !isPaused;
            runOnUiThread(() -> {
                if (pauseButton != null) {
                    pauseButton.setText(isPaused ? "RESUME" : "PAUSE");
                }
            });
        }

        public synchronized void resetGame() {
            scoreFloat = 0f;
            score = 0;
            coins = 0;
            shields = 3;
            combo = 1;
            level = 1;
            jumpsRemaining = 2;
            playerY = groundY;
            playerVelocityY = 0f;
            obstacles.clear();
            coinItems.clear();
            shieldItems.clear();
            particles.clear();
            isGameOver = false;
            isPaused = false;
            spawnTimerSec = 0f;
        }

        private void spawnParticles(float cx, float cy, int color, int count) {
            for (int i = 0; i < count; i++) {
                float vx = (random.nextFloat() - 0.5f) * 420f;
                float vy = (random.nextFloat() - 0.5f) * 420f;
                particles.add(new Particle(cx, cy, vx, vy, color));
            }
        }

        @Override
        public void run() {
            long lastNano = System.nanoTime();
            final long targetFrameNs = 16_666_667L; // 60 FPS = 16.67ms

            while (isRunningLoop) {
                long nowNano = System.nanoTime();
                float dt = (nowNano - lastNano) / 1_000_000_000.0f;
                lastNano = nowNano;
                if (dt <= 0f || dt > 0.1f) {
                    dt = 0.0166f;
                }

                if (!inTitleMenu && !isPaused && !isGameOver) {
                    updatePhysics(dt);
                }
                drawFrame();

                long workNs = System.nanoTime() - nowNano;
                float workMs = workNs / 1_000_000.0f;
                avgFrameTimeMs = 0.9f * avgFrameTimeMs + 0.1f * workMs;

                long sleepNs = targetFrameNs - workNs;
                if (sleepNs > 1_000_000L) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                long totalFrameNs = Math.max(1_000_000L, System.nanoTime() - nowNano);
                float instantFps = 1_000_000_000.0f / totalFrameNs;
                currentFps = 0.9f * currentFps + 0.1f * Math.min(60.0f, instantFps);
            }
        }

        private synchronized void updatePhysics(float dt) {
            int h = getHeight();
            int w = getWidth();
            if (h > 200) {
                groundY = h * 0.78f;
            }

            level = 1 + (score / 1000);
            float scrollSpeedPxPerSec = Math.min(820f, 380f + (level * 48f));
            gridOffset = (gridOffset + scrollSpeedPxPerSec * dt) % 60f;
            starOffset = (starOffset + scrollSpeedPxPerSec * 0.25f * dt) % Math.max(1, w);

            playerVelocityY += gravityPxPerSec2 * dt;
            playerY += playerVelocityY * dt;

            if (playerY >= groundY) {
                playerY = groundY;
                playerVelocityY = 0f;
                jumpsRemaining = 2;
            }

            scoreFloat += (60f * dt * combo);
            score = (int) scoreFloat;
            if (score > highScore) {
                saveHighScore(score);
            }

            spawnTimerSec += dt;
            float spawnIntervalSec = Math.max(0.75f, 1.65f - (level * 0.09f));
            if (spawnTimerSec >= spawnIntervalSec && w > 0) {
                spawnTimerSec = 0f;
                int roll = random.nextInt(100);
                if (roll < 28) {
                    float droneY = groundY - 95 - random.nextInt(60);
                    obstacles.add(new Obstacle(new RectF(w + 60, droneY, w + 120, droneY + 38), OBS_AERIAL_DRONE));
                } else if (roll < 55) {
                    float towerH = 95 + random.nextInt(45);
                    obstacles.add(new Obstacle(new RectF(w + 60, groundY + 50 - towerH, w + 96, groundY + 50), OBS_LASER_TOWER));
                } else {
                    float obsWidth = 44 + random.nextInt(32);
                    float obsHeight = 58 + random.nextInt(48);
                    obstacles.add(new Obstacle(new RectF(w + 60, groundY + 50 - obsHeight, w + 60 + obsWidth, groundY + 50), OBS_GROUND_SPIKE));
                }

                int coinCount = 1 + random.nextInt(3);
                for (int c = 0; c < coinCount; c++) {
                    float cx = w + 130 + (c * 52);
                    float cy = groundY - 90 - (c % 2 == 0 ? 0 : 32);
                    coinItems.add(new RectF(cx, cy, cx + 28, cy + 28));
                }

                if (shields < 3 && random.nextInt(100) < 18) {
                    shieldItems.add(new RectF(w + 260, groundY - 140, w + 294, groundY - 106));
                }
            }

            // Slightly forgiving hitbox for fair gameplay
            RectF playerRect = new RectF(playerX + 5, playerY + 5, playerX + 41, playerY + 46);
            long now = System.currentTimeMillis();

            for (int i = obstacles.size() - 1; i >= 0; i--) {
                Obstacle obs = obstacles.get(i);
                obs.rect.offset(-scrollSpeedPxPerSec * dt, 0);
                if (RectF.intersects(obs.rect, playerRect)) {
                    if (now > invulnerableUntil) {
                        shields--;
                        combo = 1;
                        invulnerableUntil = now + 1200;
                        spawnParticles(playerX + 24, playerY + 24, Color.parseColor("#FF0055"), 18);
                        playSynthesizedSfx(220, 110, 140);
                        if (shields <= 0) {
                            isGameOver = true;
                        }
                    }
                    obstacles.remove(i);
                } else if (obs.rect.right < 0) {
                    obstacles.remove(i);
                    scoreFloat += 15 * combo;
                }
            }

            for (int i = coinItems.size() - 1; i >= 0; i--) {
                RectF c = coinItems.get(i);
                c.offset(-scrollSpeedPxPerSec * dt, 0);
                if (RectF.intersects(c, playerRect)) {
                    coins++;
                    combo = Math.min(8, combo + 1);
                    scoreFloat += 50 * combo;
                    spawnParticles(c.centerX(), c.centerY(), Color.parseColor("#F59E0B"), 10);
                    playSynthesizedSfx(660, 990, 55);
                    coinItems.remove(i);
                } else if (c.right < 0) {
                    coinItems.remove(i);
                }
            }

            for (int i = shieldItems.size() - 1; i >= 0; i--) {
                RectF s = shieldItems.get(i);
                s.offset(-scrollSpeedPxPerSec * dt, 0);
                if (RectF.intersects(s, playerRect)) {
                    shields = Math.min(3, shields + 1);
                    spawnParticles(s.centerX(), s.centerY(), Color.parseColor("#10B981"), 12);
                    playSynthesizedSfx(520, 880, 90);
                    shieldItems.remove(i);
                } else if (s.right < 0) {
                    shieldItems.remove(i);
                }
            }

            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.alpha -= 2.2f * dt;
                if (p.alpha <= 0) {
                    particles.remove(i);
                }
            }
        }

        private synchronized void drawFrame() {
            if (!holder.getSurface().isValid()) return;
            Canvas canvas = holder.lockCanvas();
            if (canvas == null) return;

            try {
                int w = getWidth();
                int h = getHeight();
                if (h > 200) {
                    groundY = h * 0.78f;
                }

                canvas.drawColor(Color.parseColor("#080B11"));

                // Parallax Layer 1: Starfield
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor("#334155"));
                for (int s = 0; s < 18; s++) {
                    float sx = ((s * 137f) - starOffset + w) % Math.max(1, w);
                    float sy = 90f + ((s * 53) % (int) Math.max(100, groundY - 120));
                    canvas.drawCircle(sx, sy, (s % 2 == 0) ? 2.5f : 1.5f, paint);
                }

                // Parallax Layer 2: Distant City Skyline
                paint.setColor(Color.parseColor("#0D1526"));
                for (int i = 0; i < 12; i++) {
                    float bx = ((i * 210f) - (gridOffset * 0.5f));
                    float bh = 85f + (i % 4) * 50f;
                    canvas.drawRect(bx, groundY + 50 - bh, bx + 135, groundY + 50, paint);
                }

                // Synthwave Floor Grid
                paint.setColor(Color.parseColor("#131E32"));
                paint.setStrokeWidth(2f);
                for (float x = -gridOffset; x < w; x += 60f) {
                    canvas.drawLine(x, groundY + 50, x - 45, h, paint);
                }

                // Ground Horizon Line
                paint.setColor(Color.parseColor("#00F0FF"));
                paint.setStrokeWidth(5f);
                canvas.drawLine(0, groundY + 50, w, groundY + 50, paint);

                // Obstacles
                for (Obstacle obs : obstacles) {
                    if (obs.type == OBS_AERIAL_DRONE) {
                        paint.setColor(Color.parseColor("#A855F7"));
                        canvas.drawOval(obs.rect, paint);
                    } else if (obs.type == OBS_LASER_TOWER) {
                        paint.setColor(Color.parseColor("#F43F5E"));
                        canvas.drawRoundRect(obs.rect, 6, 6, paint);
                    } else {
                        paint.setColor(Color.parseColor("#FF0055"));
                        canvas.drawRoundRect(obs.rect, 10, 10, paint);
                    }
                }

                // Coins & Shields
                paint.setColor(Color.parseColor("#F59E0B"));
                for (RectF coin : coinItems) {
                    canvas.drawOval(coin, paint);
                }

                paint.setColor(Color.parseColor("#10B981"));
                for (RectF sh : shieldItems) {
                    canvas.drawRoundRect(sh, 14, 14, paint);
                }

                // Particles
                for (Particle pt : particles) {
                    paint.setColor(pt.color);
                    paint.setAlpha((int) (Math.max(0, pt.alpha) * 255));
                    canvas.drawCircle(pt.x, pt.y, 5f, paint);
                }
                paint.setAlpha(255);

                // Runner Character
                boolean flashing = System.currentTimeMillis() < invulnerableUntil && ((System.currentTimeMillis() / 80) % 2 == 0);
                paint.setColor(flashing ? Color.parseColor("#FF0055") : Color.parseColor("#00F0FF"));
                canvas.drawRoundRect(new RectF(playerX, playerY, playerX + 46, playerY + 50), 10, 10, paint);

                paint.setColor(Color.WHITE);
                canvas.drawRect(playerX + 24, playerY + 12, playerX + 42, playerY + 20, paint);

                // Canvas HUD (Zero UI-thread overhead)
                String shieldStr = shields == 3 ? "♥♥♥" : (shields == 2 ? "♥♥♡" : (shields == 1 ? "♥♡♡" : "♡♡♡"));
                textPaint.setColor(Color.parseColor("#00F0FF"));
                textPaint.setTextSize(24f);
                canvas.drawText(String.format(Locale.US, "SCORE: %d  |  BEST: %d  |  COINS: %d (x%d)  |  SHIELDS: %s",
                        score, highScore, coins, combo, shieldStr), 28f, h - 22f, textPaint);

                textPaint.setColor(Color.parseColor("#94A3B8"));
                textPaint.setTextSize(20f);
                canvas.drawText(String.format(Locale.US, "LVL %d  ·  %.0f FPS (%.1f ms)", level, currentFps, avgFrameTimeMs),
                        w - 340f, h - 22f, textPaint);

                if (inTitleMenu) {
                    canvas.drawColor(Color.argb(190, 8, 11, 17));
                    textPaint.setColor(Color.parseColor("#00F0FF"));
                    textPaint.setTextSize(50f);
                    canvas.drawText("SCREAM RUNNER v3.0", w / 2f - 280f, h / 2f - 50f, textPaint);

                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(26f);
                    canvas.drawText("Touch Screen or Shout Above Gate to Jump & Double-Jump", w / 2f - 410f, h / 2f + 10f, textPaint);

                    textPaint.setColor(Color.parseColor("#10B981"));
                    textPaint.setTextSize(30f);
                    canvas.drawText("[ TAP ANYWHERE TO START RUN ]", w / 2f - 260f, h / 2f + 75f, textPaint);
                } else if (isPaused) {
                    canvas.drawColor(Color.argb(175, 8, 11, 17));
                    textPaint.setColor(Color.parseColor("#00F0FF"));
                    textPaint.setTextSize(44f);
                    canvas.drawText("GAME PAUSED — TAP TO RESUME", w / 2f - 350f, h / 2f, textPaint);
                } else if (isGameOver) {
                    canvas.drawColor(Color.argb(205, 8, 11, 17));
                    textPaint.setColor(Color.parseColor("#FF0055"));
                    textPaint.setTextSize(50f);
                    canvas.drawText("RUN COMPLETE", w / 2f - 200f, h / 2f - 50f, textPaint);

                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(30f);
                    canvas.drawText("FINAL SCORE: " + score + "   |   BEST: " + highScore + "   |   COINS: " + coins,
                            w / 2f - 370f, h / 2f + 15f, textPaint);

                    textPaint.setColor(Color.parseColor("#00F0FF"));
                    textPaint.setTextSize(28f);
                    canvas.drawText("[ TAP ANYWHERE TO PLAY AGAIN ]", w / 2f - 260f, h / 2f + 75f, textPaint);
                }

            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                handleActionTap(0.9);
                return true;
            }
            return super.onTouchEvent(event);
        }

        public void resumeLoop() {
            if (isRunningLoop) return;
            isRunningLoop = true;
            gameThread = new Thread(this, "ScreamRunnerRenderThread");
            gameThread.start();
        }

        public void pauseLoop() {
            isRunningLoop = false;
            try {
                if (gameThread != null) {
                    gameThread.join(400);
                    gameThread = null;
                }
            } catch (InterruptedException ignored) {}
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            resumeLoop();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            pauseLoop();
        }
    }
}
