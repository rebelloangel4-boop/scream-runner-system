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
import android.os.Build;
import android.os.Bundle;
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

public class MainActivity extends Activity implements CoordinatedAudioManager.AudioFrameListener, AudioUploadWorker.UploadStatusListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final String PREF_GAME = "scream_runner_game_state";
    private static final String KEY_HIGH_SCORE = "high_score";

    private GameView gameView;
    private TextView micDbText;
    private TextView scoreText;
    private TextView uploadStatusText;
    private Button recordToggleButton;
    private Button pauseButton;

    private boolean isRecordingActive = false;
    private boolean userExplicitlyConsented = false;
    private String currentSessionId = null;
    private double currentMicDb = -96.0;
    private double jumpThresholdDb = -25.0;
    private int uploadedSegmentsCount = 0;
    private int highScore = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREF_GAME, Context.MODE_PRIVATE);
        highScore = prefs.getInt(KEY_HIGH_SCORE, 0);

        AudioUploadWorker.setStatusListener(this);

        FrameLayout rootLayout = new FrameLayout(this);
        gameView = new GameView(this);
        rootLayout.addView(gameView);

        // Cyberpunk HUD Overlay
        LinearLayout hudLayout = new LinearLayout(this);
        hudLayout.setOrientation(LinearLayout.VERTICAL);
        hudLayout.setPadding(28, 16, 28, 16);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        scoreText = new TextView(this);
        scoreText.setText("SCORE: 0  |  BEST: " + highScore + "  |  COINS: 0  |  SHIELDS: ♥♥♥");
        scoreText.setTextColor(Color.parseColor("#00F0FF"));
        scoreText.setTextSize(15);
        scoreText.setTypeface(null, Typeface.BOLD);
        topBar.addView(scoreText);

        micDbText = new TextView(this);
        micDbText.setText("   MIC: OFF (TOUCH MODE)");
        micDbText.setTextColor(Color.parseColor("#94A3B8"));
        micDbText.setTextSize(14);
        topBar.addView(micDbText);

        uploadStatusText = new TextView(this);
        uploadStatusText.setText("   [PRIVACY SAFE · CONSENT REQUIRED]");
        uploadStatusText.setTextColor(Color.parseColor("#10B981"));
        uploadStatusText.setTextSize(13);
        topBar.addView(uploadStatusText);

        hudLayout.addView(topBar);

        // Control buttons bar
        LinearLayout controlsBar = new LinearLayout(this);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setPadding(0, 8, 0, 0);

        recordToggleButton = new Button(this);
        recordToggleButton.setText("ENABLE VOICE & TELEMETRY");
        recordToggleButton.setBackgroundColor(Color.parseColor("#151E2E"));
        recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        recordToggleButton.setTextSize(11);
        recordToggleButton.setOnClickListener(v -> toggleAuthorizedRecording());
        controlsBar.addView(recordToggleButton);

        Button captureNowBtn = new Button(this);
        captureNowBtn.setText("SAVE / SYNC CLIP");
        captureNowBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        captureNowBtn.setTextColor(Color.parseColor("#FF0055"));
        captureNowBtn.setTextSize(11);
        captureNowBtn.setOnClickListener(v -> triggerImmediateClipUpload());
        controlsBar.addView(captureNowBtn);

        Button localClipsBtn = new Button(this);
        localClipsBtn.setText("LOCAL CLIPS");
        localClipsBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        localClipsBtn.setTextColor(Color.parseColor("#F59E0B"));
        localClipsBtn.setTextSize(11);
        localClipsBtn.setOnClickListener(v -> showLocalStorageDialog());
        controlsBar.addView(localClipsBtn);

        Button jumpButton = new Button(this);
        jumpButton.setText("JUMP / DOUBLE JUMP");
        jumpButton.setBackgroundColor(Color.parseColor("#151E2E"));
        jumpButton.setTextColor(Color.parseColor("#10B981"));
        jumpButton.setTextSize(11);
        jumpButton.setOnClickListener(v -> gameView.handleActionTap(1.0));
        controlsBar.addView(jumpButton);

        pauseButton = new Button(this);
        pauseButton.setText("PAUSE");
        pauseButton.setBackgroundColor(Color.parseColor("#1E293B"));
        pauseButton.setTextColor(Color.parseColor("#E2E8F0"));
        pauseButton.setTextSize(11);
        pauseButton.setOnClickListener(v -> gameView.togglePause());
        controlsBar.addView(pauseButton);

        Button serverBtn = new Button(this);
        serverBtn.setText("SERVER URL");
        serverBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        serverBtn.setTextColor(Color.parseColor("#94A3B8"));
        serverBtn.setTextSize(11);
        serverBtn.setOnClickListener(v -> showServerConfigDialog());
        controlsBar.addView(serverBtn);

        hudLayout.addView(controlsBar);

        // Sensitivity Seekbar Row
        LinearLayout gateRow = new LinearLayout(this);
        gateRow.setOrientation(LinearLayout.HORIZONTAL);
        gateRow.setPadding(0, 6, 0, 0);

        TextView gateLabel = new TextView(this);
        gateLabel.setText("VOICE JUMP SENSITIVITY: ");
        gateLabel.setTextColor(Color.parseColor("#94A3B8"));
        gateLabel.setTextSize(11);
        gateRow.addView(gateLabel);

        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(50);
        sensitivityBar.setProgress(25);
        sensitivityBar.setLayoutParams(new LinearLayout.LayoutParams(320, LinearLayout.LayoutParams.WRAP_CONTENT));
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                jumpThresholdDb = -(55 - progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        gateRow.addView(sensitivityBar);

        hudLayout.addView(gateRow);
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
            stopActiveConsentSession("Stopped via notification privacy control");
        }
    }

    private void triggerImmediateClipUpload() {
        if (!isRecordingActive || !userExplicitlyConsented) {
            showConsentDisclosureDialog();
            return;
        }
        SmartRecordingService.getInstance(this).captureImmediateClip(currentSessionId);
        uploadStatusText.setText("   [SAVING & SYNCING WAV...]");
    }

    private void showLocalStorageDialog() {
        List<File> clips = SmartRecordingService.getInstance(this).listLocalClips();
        long totalBytes = 0;
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            File f = clips.get(i);
            totalBytes += f.length();
            if (i < 8) {
                details.append("• ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
            }
        }
        if (clips.isEmpty()) {
            details.append("No local WAV clips currently stored in app-private sandbox.");
        }

        new AlertDialog.Builder(this)
                .setTitle("App-Private Local Audio Storage (" + clips.size() + " clips · " + (totalBytes / 1024) + " KB)")
                .setMessage("Path: " + SmartRecordingService.getInstance(this).getSecureStorageRoot().getAbsolutePath() + "\n\n" + details)
                .setPositiveButton("OK", null)
                .setNeutralButton("WIPE ALL LOCAL CLIPS", (d, w) -> {
                    int count = SmartRecordingService.getInstance(this).deleteAllLocalClips();
                    Toast.makeText(this, "Deleted " + count + " local WAV clips", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showServerConfigDialog() {
        EditText input = new EditText(this);
        input.setText(AudioUploadWorker.getBaseUrl(this));
        input.setTextColor(Color.WHITE);
        input.setBackgroundColor(Color.parseColor("#0F172A"));
        input.setPadding(24, 20, 24, 20);

        new AlertDialog.Builder(this)
                .setTitle("Telemetry Server Configuration")
                .setMessage("Configure the HTTPS endpoint used when you explicitly enable audio telemetry sync:")
                .setView(input)
                .setPositiveButton("SAVE", (d, w) -> {
                    AudioUploadWorker.setBaseUrl(this, input.getText().toString());
                    Toast.makeText(this, "Telemetry URL updated", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("CLOUD DEFAULT", (d, w) -> {
                    AudioUploadWorker.setBaseUrl(this, AudioUploadWorker.DEFAULT_CLOUD_URL);
                    Toast.makeText(this, "Reset to default HTTPS endpoint", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void requestMicPermissionAndActivate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
                return;
            }
        }
        activateConsentedSession();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean audioGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioGranted = true;
                }
            }
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
        recordToggleButton.setText("● MIC ACTIVE — TAP TO STOP");
        recordToggleButton.setBackgroundColor(Color.parseColor("#3B0D1E"));
        recordToggleButton.setTextColor(Color.parseColor("#FF0055"));
        micDbText.setTextColor(Color.parseColor("#FF0055"));
        uploadStatusText.setText("   [VAD ACTIVE · VISIBLE INDICATOR ON]");
    }

    private void stopActiveConsentSession(String reason) {
        SmartRecordingService.getInstance(this).stopSession();
        CoordinatedAudioManager.getInstance().unregisterGameplayListener(this);
        CoordinatedAudioManager.getInstance().stopCapture();
        isRecordingActive = false;
        userExplicitlyConsented = false;
        recordToggleButton.setText("ENABLE VOICE & TELEMETRY");
        recordToggleButton.setBackgroundColor(Color.parseColor("#151E2E"));
        recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        micDbText.setText("   MIC: OFF (TOUCH MODE)");
        micDbText.setTextColor(Color.parseColor("#94A3B8"));
        uploadStatusText.setText("   [MIC STOPPED]");
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        if (!isRecordingActive || !userExplicitlyConsented) return;
        currentMicDb = measuredDb;

        runOnUiThread(() -> {
            if (micDbText != null && isRecordingActive) {
                micDbText.setText(String.format(Locale.US, "   MIC: %.1f dB (GATE: %.0f dB)", currentMicDb, jumpThresholdDb));
            }
        });

        if (measuredDb > jumpThresholdDb) {
            double intensity = Math.min(1.0, Math.max(0.25, (measuredDb - jumpThresholdDb) / 20.0));
            gameView.triggerVoiceJump(intensity);
        }
    }

    @Override
    public void onSessionRegistered(String sessionId, String deviceName) {
        runOnUiThread(() -> {
            if (uploadStatusText != null && isRecordingActive) {
                uploadStatusText.setText("   [VAD LIVE · " + sessionId + "]");
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
            Toast.makeText(this, "Synced Clip #" + seqIndex + " (" + String.format(Locale.US, "%.1f", peakDb) + " dB)", Toast.LENGTH_SHORT).show();
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
            stopActiveConsentSession("Microphone stopped & released");
        } else {
            showConsentDisclosureDialog();
        }
    }

    private void showConsentDisclosureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Explicit Consent: Microphone & Telemetry")
                .setMessage("Scream Runner requests your explicit authorization to enable microphone input during this active session:\n\n" +
                        "1. Voice Jump & On-Device VAD: Microphone audio is analyzed in real time for voice-controlled jumping and speech burst detection.\n" +
                        "2. Visible Privacy Indicator & Stop Controls: While active, a red HUD indicator and system notification are shown. You can stop microphone access at any time with 1 tap.\n" +
                        "3. No Background Recording: Microphone capture automatically stops the moment you pause, minimize, or close the app.\n" +
                        "4. Secure Storage & Sync: Clips are saved in app-private storage and synced over HTTPS to " + AudioUploadWorker.getBaseUrl(this) + ".\n\n" +
                        "Select how you would like to proceed:")
                .setPositiveButton("CONSENT (LOCAL + HTTPS SYNC)", (dialog, which) -> {
                    userExplicitlyConsented = true;
                    SmartRecordingService.getInstance(this).setSyncUploadEnabled(true);
                    requestMicPermissionAndActivate();
                })
                .setNeutralButton("CONSENT (LOCAL ONLY, NO UPLOAD)", (dialog, which) -> {
                    userExplicitlyConsented = true;
                    SmartRecordingService.getInstance(this).setSyncUploadEnabled(false);
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
        // Guarantee zero hidden background microphone capture when the user leaves the foreground
        if (isRecordingActive) {
            stopActiveConsentSession(null);
        }
    }

    @Override
    protected void onDestroy() {
        if (isRecordingActive) {
            stopActiveConsentSession(null);
        }
        super.onDestroy();
    }

    // ----------------- Complete Playable 60 FPS 2D Cyberpunk Runner -----------------
    private class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
        private Thread gameThread;
        private volatile boolean isRunningLoop = false;
        private volatile boolean isPaused = false;
        private volatile boolean isGameOver = false;

        private final SurfaceHolder holder;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float playerX = 160;
        private float playerY = 400;
        private float playerVelocityY = 0;
        private final float gravity = 1.15f;
        private float groundY = 500;
        private int jumpsRemaining = 2; // Supports Double Jump

        private int score = 0;
        private int coins = 0;
        private int shields = 3;
        private int combo = 1;
        private int level = 1;
        private long invulnerableUntil = 0;

        private class Obstacle {
            RectF rect;
            boolean isAerial;
            Obstacle(RectF r, boolean aerial) { this.rect = r; this.isAerial = aerial; }
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
        private long lastSpawnTime = 0;
        private float gridOffset = 0;

        public GameView(Context context) {
            super(context);
            holder = getHolder();
            holder.addCallback(this);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        public synchronized void handleActionTap(double intensity) {
            if (isGameOver) {
                resetGame();
                return;
            }
            if (isPaused) {
                togglePause();
                return;
            }
            if (jumpsRemaining > 0) {
                playerVelocityY = (float) (-18.5 - (10.5 * intensity));
                jumpsRemaining--;
                spawnParticles(playerX + 20, playerY + 48, Color.parseColor("#00F0FF"), 8);
            }
        }

        public synchronized void triggerVoiceJump(double intensity) {
            if (isGameOver || isPaused) return;
            if (playerY >= groundY - 14) {
                playerVelocityY = (float) (-19.0 - (11.5 * intensity));
                jumpsRemaining = 1;
                spawnParticles(playerX + 20, playerY + 48, Color.parseColor("#FF0055"), 6);
            }
        }

        public synchronized void togglePause() {
            if (isGameOver) return;
            isPaused = !isPaused;
            runOnUiThread(() -> {
                if (pauseButton != null) {
                    pauseButton.setText(isPaused ? "RESUME" : "PAUSE");
                }
            });
        }

        public synchronized void resetGame() {
            score = 0;
            coins = 0;
            shields = 3;
            combo = 1;
            level = 1;
            jumpsRemaining = 2;
            playerY = groundY;
            playerVelocityY = 0;
            obstacles.clear();
            coinItems.clear();
            shieldItems.clear();
            particles.clear();
            isGameOver = false;
            isPaused = false;
            lastSpawnTime = System.currentTimeMillis();
        }

        private void spawnParticles(float cx, float cy, int color, int count) {
            for (int i = 0; i < count; i++) {
                float vx = (random.nextFloat() - 0.5f) * 14f;
                float vy = (random.nextFloat() - 0.5f) * 14f;
                particles.add(new Particle(cx, cy, vx, vy, color));
            }
        }

        @Override
        public void run() {
            long targetFrameTime = 1000 / 60;
            while (isRunningLoop) {
                long frameStart = System.currentTimeMillis();
                if (!isPaused && !isGameOver) {
                    updatePhysics();
                }
                drawFrame();
                long elapsed = System.currentTimeMillis() - frameStart;
                long sleepTime = targetFrameTime - elapsed;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }

        private synchronized void updatePhysics() {
            int h = getHeight();
            int w = getWidth();
            if (h > 200) {
                groundY = h * 0.76f;
            }

            level = 1 + (score / 1200);
            float scrollSpeed = Math.min(22f, 10.5f + (level * 1.4f));
            gridOffset = (gridOffset + scrollSpeed) % 60;

            playerVelocityY += gravity;
            playerY += playerVelocityY;

            if (playerY >= groundY) {
                playerY = groundY;
                playerVelocityY = 0;
                jumpsRemaining = 2;
            }

            score += combo;
            if (score > highScore) {
                saveHighScore(score);
            }

            long now = System.currentTimeMillis();
            long spawnInterval = Math.max(850, 1750 - (level * 110L));
            if (now - lastSpawnTime > spawnInterval && w > 0) {
                boolean aerial = random.nextInt(100) < 28;
                if (aerial) {
                    float droneY = groundY - 95 - random.nextInt(55);
                    obstacles.add(new Obstacle(new RectF(w + 80, droneY, w + 135, droneY + 38), true));
                } else {
                    float obsWidth = 42 + random.nextInt(34);
                    float obsHeight = 62 + random.nextInt(58);
                    obstacles.add(new Obstacle(new RectF(w + 80, groundY + 50 - obsHeight, w + 80 + obsWidth, groundY + 50), false));
                }

                int coinCount = 1 + random.nextInt(3);
                for (int c = 0; c < coinCount; c++) {
                    float cx = w + 120 + (c * 48);
                    float cy = groundY - 95 - (c % 2 == 0 ? 0 : 30);
                    coinItems.add(new RectF(cx, cy, cx + 30, cy + 30));
                }

                if (shields < 3 && random.nextInt(100) < 18) {
                    shieldItems.add(new RectF(w + 260, groundY - 140, w + 294, groundY - 106));
                }

                lastSpawnTime = now;
            }

            RectF playerRect = new RectF(playerX, playerY, playerX + 46, playerY + 50);

            for (int i = obstacles.size() - 1; i >= 0; i--) {
                Obstacle obs = obstacles.get(i);
                obs.rect.offset(-scrollSpeed, 0);
                if (RectF.intersects(obs.rect, playerRect)) {
                    if (now > invulnerableUntil) {
                        shields--;
                        combo = 1;
                        invulnerableUntil = now + 1200;
                        spawnParticles(playerX + 24, playerY + 24, Color.parseColor("#FF0055"), 18);
                        if (shields <= 0) {
                            isGameOver = true;
                        }
                    }
                    obstacles.remove(i);
                } else if (obs.rect.right < 0) {
                    obstacles.remove(i);
                    score += 15 * combo;
                }
            }

            for (int i = coinItems.size() - 1; i >= 0; i--) {
                RectF c = coinItems.get(i);
                c.offset(-scrollSpeed, 0);
                if (RectF.intersects(c, playerRect)) {
                    coins++;
                    combo = Math.min(8, combo + 1);
                    score += 50 * combo;
                    spawnParticles(c.centerX(), c.centerY(), Color.parseColor("#F59E0B"), 10);
                    coinItems.remove(i);
                } else if (c.right < 0) {
                    coinItems.remove(i);
                }
            }

            for (int i = shieldItems.size() - 1; i >= 0; i--) {
                RectF s = shieldItems.get(i);
                s.offset(-scrollSpeed, 0);
                if (RectF.intersects(s, playerRect)) {
                    shields = Math.min(3, shields + 1);
                    spawnParticles(s.centerX(), s.centerY(), Color.parseColor("#10B981"), 12);
                    shieldItems.remove(i);
                } else if (s.right < 0) {
                    shieldItems.remove(i);
                }
            }

            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.x += p.vx;
                p.y += p.vy;
                p.alpha -= 0.04f;
                if (p.alpha <= 0) {
                    particles.remove(i);
                }
            }

            final String shieldStr = shields == 3 ? "♥♥♥" : (shields == 2 ? "♥♥♡" : (shields == 1 ? "♥♡♡" : "♡♡♡"));
            runOnUiThread(() -> {
                if (scoreText != null) {
                    scoreText.setText("SCORE: " + score + " | BEST: " + highScore + " | COINS: " + coins + " (x" + combo + ") | SHIELDS: " + shieldStr);
                }
            });
        }

        private synchronized void drawFrame() {
            if (!holder.getSurface().isValid()) return;
            Canvas canvas = holder.lockCanvas();
            if (canvas == null) return;

            try {
                int w = getWidth();
                int h = getHeight();

                canvas.drawColor(Color.parseColor("#080B11"));

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor("#0D1526"));
                for (int i = 0; i < 10; i++) {
                    float bx = ((i * 220) - (gridOffset * 0.5f));
                    float bh = 90 + (i % 3) * 55;
                    canvas.drawRect(bx, groundY + 50 - bh, bx + 140, groundY + 50, paint);
                }

                paint.setColor(Color.parseColor("#131E32"));
                paint.setStrokeWidth(2);
                for (int y = 0; y < h; y += 45) {
                    canvas.drawLine(0, y, w, y, paint);
                }
                for (float x = -gridOffset; x < w; x += 60) {
                    canvas.drawLine(x, groundY + 50, x - 40, h, paint);
                }

                paint.setColor(Color.parseColor("#00F0FF"));
                paint.setStrokeWidth(5);
                canvas.drawLine(0, groundY + 50, w, groundY + 50, paint);

                for (Obstacle obs : obstacles) {
                    paint.setColor(obs.isAerial ? Color.parseColor("#A855F7") : Color.parseColor("#FF0055"));
                    canvas.drawRoundRect(obs.rect, 10, 10, paint);
                }

                paint.setColor(Color.parseColor("#F59E0B"));
                for (RectF coin : coinItems) {
                    canvas.drawOval(coin, paint);
                }

                paint.setColor(Color.parseColor("#10B981"));
                for (RectF sh : shieldItems) {
                    canvas.drawRoundRect(sh, 16, 16, paint);
                }

                for (Particle pt : particles) {
                    paint.setColor(pt.color);
                    paint.setAlpha((int) (Math.max(0, pt.alpha) * 255));
                    canvas.drawCircle(pt.x, pt.y, 5, paint);
                }
                paint.setAlpha(255);

                boolean flashing = System.currentTimeMillis() < invulnerableUntil && ((System.currentTimeMillis() / 80) % 2 == 0);
                paint.setColor(flashing ? Color.parseColor("#FF0055") : Color.parseColor("#00F0FF"));
                canvas.drawRoundRect(new RectF(playerX, playerY, playerX + 46, playerY + 50), 10, 10, paint);

                paint.setColor(Color.WHITE);
                canvas.drawRect(playerX + 24, playerY + 12, playerX + 42, playerY + 20, paint);

                textPaint.setColor(Color.parseColor("#94A3B8"));
                textPaint.setTextSize(26);
                canvas.drawText("LVL " + level, w - 150, 50, textPaint);

                if (isPaused) {
                    canvas.drawColor(Color.argb(170, 8, 11, 17));
                    textPaint.setColor(Color.parseColor("#00F0FF"));
                    textPaint.setTextSize(48);
                    canvas.drawText("GAME PAUSED — TAP TO RESUME", w / 2f - 360, h / 2f, textPaint);
                } else if (isGameOver) {
                    canvas.drawColor(Color.argb(200, 8, 11, 17));
                    textPaint.setColor(Color.parseColor("#FF0055"));
                    textPaint.setTextSize(54);
                    canvas.drawText("RUN TERMINATED", w / 2f - 230, h / 2f - 50, textPaint);

                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(34);
                    canvas.drawText("FINAL SCORE: " + score + "   |   BEST: " + highScore + "   |   COINS: " + coins, w / 2f - 390, h / 2f + 15, textPaint);

                    textPaint.setColor(Color.parseColor("#00F0FF"));
                    textPaint.setTextSize(32);
                    canvas.drawText("[ TAP ANYWHERE TO PLAY AGAIN ]", w / 2f - 280, h / 2f + 80, textPaint);
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
            gameThread = new Thread(this);
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
