package com.screamrunner.game;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Button;

import com.screamrunner.game.audio.CoordinatedAudioManager;
import com.screamrunner.game.audio.SmartRecordingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity implements CoordinatedAudioManager.AudioFrameListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private GameView gameView;
    private TextView micDbText;
    private TextView scoreText;
    private Button recordToggleButton;
    private SeekBar sensitivityBar;

    private boolean isRecordingActive = false;
    private String currentSessionId = null;
    private double currentMicDb = -96.0;
    private double jumpThresholdDb = -25.0; // Adjustable threshold

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
        gameView = new GameView(this);
        rootLayout.addView(gameView);

        // Cyberpunk HUD Overlay
        LinearLayout hudLayout = new LinearLayout(this);
        hudLayout.setOrientation(LinearLayout.VERTICAL);
        hudLayout.setPadding(30, 20, 30, 20);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        scoreText = new TextView(this);
        scoreText.setText("SCORE: 0  |  COINS: 0");
        scoreText.setTextColor(Color.parseColor("#00F0FF"));
        scoreText.setTextSize(18);
        topBar.addView(scoreText);

        micDbText = new TextView(this);
        micDbText.setText("   MIC: -96 dB (GATE: -25 dB)");
        micDbText.setTextColor(Color.parseColor("#FF0055"));
        micDbText.setTextSize(16);
        topBar.addView(micDbText);

        hudLayout.addView(topBar);

        // Control buttons bar
        LinearLayout controlsBar = new LinearLayout(this);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setPadding(0, 10, 0, 0);

        recordToggleButton = new Button(this);
        recordToggleButton.setText("START AUTHORIZED RECORDING");
        recordToggleButton.setBackgroundColor(Color.parseColor("#151E2E"));
        recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        recordToggleButton.setOnClickListener(v -> toggleAuthorizedRecording());
        controlsBar.addView(recordToggleButton);

        Button jumpButton = new Button(this);
        jumpButton.setText("TOUCH JUMP (FALLBACK)");
        jumpButton.setBackgroundColor(Color.parseColor("#151E2E"));
        jumpButton.setTextColor(Color.parseColor("#10B981"));
        jumpButton.setOnClickListener(v -> gameView.triggerJump(1.0));
        controlsBar.addView(jumpButton);

        hudLayout.addView(controlsBar);
        rootLayout.addView(hudLayout);

        setContentView(rootLayout);

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), PERMISSION_REQ_CODE);
                return;
            }
        }
        startMicrophoneListening();
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
            if (audioGranted) {
                startMicrophoneListening();
            }
        }
    }

    private void startMicrophoneListening() {
        CoordinatedAudioManager.getInstance().registerGameplayListener(this);
    }

    @Override
    public void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        currentMicDb = measuredDb;

        runOnUiThread(() -> {
            if (micDbText != null) {
                micDbText.setText(String.format("   MIC: %.1f dB (GATE: %.0f dB)", currentMicDb, jumpThresholdDb));
            }
        });

        // Trigger jump if measured audio loudness exceeds threshold
        if (measuredDb > jumpThresholdDb) {
            double intensity = Math.min(1.0, Math.max(0.2, (measuredDb - jumpThresholdDb) / 20.0));
            gameView.triggerJump(intensity);
        }
    }

    private void toggleAuthorizedRecording() {
        if (isRecordingActive) {
            // Stop recording
            Intent stopIntent = new Intent(this, SmartRecordingService.class);
            stopIntent.setAction(SmartRecordingService.ACTION_STOP);
            startService(stopIntent);
            isRecordingActive = false;
            recordToggleButton.setText("START AUTHORIZED RECORDING");
            recordToggleButton.setTextColor(Color.parseColor("#00F0FF"));
        } else {
            // Show explicit consent disclosure dialog
            showConsentDisclosureDialog();
        }
    }

    private void showConsentDisclosureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Explicit Consent: Smart Audio Recording")
                .setMessage("You are authorizing Scream Runner to capture audio using Voice Activity Detection (VAD) during this session.\n\n" +
                        "• Silence is discarded; only speech bursts are retained.\n" +
                        "• A persistent notification with an immediate STOP button will remain visible.\n" +
                        "• Up to 6 hours active duration supported.\n" +
                        "• Speech segments are securely encrypted and synchronized to Sam's Dashboard.\n\n" +
                        "Do you explicitly grant consent to begin this recording session?")
                .setPositiveButton("I CONSENT & ACTIVATE", (dialog, which) -> {
                    currentSessionId = "sess_" + System.currentTimeMillis();
                    Intent startIntent = new Intent(this, SmartRecordingService.class);
                    startIntent.setAction(SmartRecordingService.ACTION_START);
                    startIntent.putExtra(SmartRecordingService.EXTRA_SESSION_ID, currentSessionId);
                    if (Build.VERSION.SDK_INT >= 26) {
                        try {
                            java.lang.reflect.Method m = Context.class.getMethod("startForegroundService", Intent.class);
                            m.invoke(this, startIntent);
                        } catch (Exception e) {
                            startService(startIntent);
                        }
                    } else {
                        startService(startIntent);
                    }
                    isRecordingActive = true;
                    recordToggleButton.setText("STOP RECORDING (ACTIVE)");
                    recordToggleButton.setTextColor(Color.parseColor("#FF0055"));
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resumeGame();
        CoordinatedAudioManager.getInstance().registerGameplayListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pauseGame();
        CoordinatedAudioManager.getInstance().unregisterGameplayListener(this);
    }

    // ----------------- 60 FPS 2D Cyberpunk Runner Canvas -----------------
    private class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
        private Thread gameThread;
        private volatile boolean isPlaying = false;
        private final SurfaceHolder holder;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Player physics
        private float playerX = 150;
        private float playerY = 400;
        private float playerVelocityY = 0;
        private final float gravity = 1.1f;
        private final float groundY = 500;
        private int score = 0;
        private int coins = 0;

        // Obstacles & Coins
        private final List<RectF> obstacles = new ArrayList<>();
        private final List<RectF> coinItems = new ArrayList<>();
        private final Random random = new Random();
        private long lastSpawnTime = 0;

        public GameView(Context context) {
            super(context);
            holder = getHolder();
            holder.addCallback(this);
        }

        public void triggerJump(double intensity) {
            if (playerY >= groundY - 10) { // On ground
                playerVelocityY = (float) (-18.0 - (10.0 * intensity));
            }
        }

        @Override
        public void run() {
            long targetFrameTime = 1000 / 60; // 60 FPS
            while (isPlaying) {
                long frameStart = System.currentTimeMillis();

                updatePhysics();
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

        private void updatePhysics() {
            // Apply gravity
            playerVelocityY += gravity;
            playerY += playerVelocityY;

            if (playerY > groundY) {
                playerY = groundY;
                playerVelocityY = 0;
            }

            score++;

            // Move and spawn obstacles
            long now = System.currentTimeMillis();
            if (now - lastSpawnTime > 1800) {
                float obsWidth = 40 + random.nextInt(30);
                float obsHeight = 60 + random.nextInt(50);
                obstacles.add(new RectF(getWidth() + 100, groundY + 40 - obsHeight, getWidth() + 100 + obsWidth, groundY + 40));
                
                // Spawn coins
                coinItems.add(new RectF(getWidth() + 120, groundY - 120, getWidth() + 150, groundY - 90));
                lastSpawnTime = now;
            }

            // Move obstacles left
            for (int i = obstacles.size() - 1; i >= 0; i--) {
                RectF obs = obstacles.get(i);
                obs.offset(-9, 0);
                if (obs.right < 0) {
                    obstacles.remove(i);
                }
            }

            // Move coins left
            for (int i = coinItems.size() - 1; i >= 0; i--) {
                RectF c = coinItems.get(i);
                c.offset(-9, 0);
                // Coin collision
                if (RectF.intersects(c, new RectF(playerX, playerY, playerX + 40, playerY + 50))) {
                    coins++;
                    coinItems.remove(i);
                } else if (c.right < 0) {
                    coinItems.remove(i);
                }
            }

            runOnUiThread(() -> {
                if (scoreText != null) {
                    scoreText.setText("SCORE: " + score + "  |  COINS: " + coins);
                }
            });
        }

        private void drawFrame() {
            if (!holder.getSurface().isValid()) return;
            Canvas canvas = holder.lockCanvas();
            if (canvas == null) return;

            try {
                // Background
                canvas.drawColor(Color.parseColor("#080B11"));

                // Synthwave glowing grid lines
                paint.setColor(Color.parseColor("#151E2E"));
                paint.setStrokeWidth(2);
                for (int y = 0; y < getHeight(); y += 40) {
                    canvas.drawLine(0, y, getWidth(), y, paint);
                }

                // Ground line
                paint.setColor(Color.parseColor("#00F0FF"));
                paint.setStrokeWidth(4);
                canvas.drawLine(0, groundY + 40, getWidth(), groundY + 40, paint);

                // Draw obstacles (Glowing Neon Magenta)
                paint.setColor(Color.parseColor("#FF0055"));
                paint.setStyle(Paint.Style.FILL);
                for (RectF obs : obstacles) {
                    canvas.drawRoundRect(obs, 8, 8, paint);
                }

                // Draw coins (Glowing Amber Gold)
                paint.setColor(Color.parseColor("#F59E0B"));
                for (RectF coin : coinItems) {
                    canvas.drawOval(coin, paint);
                }

                // Draw player (Cyberpunk Runner Sprite)
                paint.setColor(Color.parseColor("#00F0FF"));
                canvas.drawRoundRect(new RectF(playerX, playerY, playerX + 40, playerY + 50), 10, 10, paint);

                // Eye / Visor
                paint.setColor(Color.parseColor("#FFFFFF"));
                canvas.drawRect(playerX + 22, playerY + 12, playerX + 38, playerY + 20, paint);

            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                triggerJump(0.8);
                return true;
            }
            return super.onTouchEvent(event);
        }

        public void resumeGame() {
            isPlaying = true;
            gameThread = new Thread(this);
            gameThread.start();
        }

        public void pauseGame() {
            isPlaying = false;
            try {
                if (gameThread != null) {
                    gameThread.join();
                }
            } catch (InterruptedException ignored) {}
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            resumeGame();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            pauseGame();
        }
    }
}
