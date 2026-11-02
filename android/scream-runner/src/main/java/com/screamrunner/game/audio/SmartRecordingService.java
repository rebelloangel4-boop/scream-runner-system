package com.screamrunner.game.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.screamrunner.game.MainActivity;
import com.screamrunner.game.upload.AudioUploadWorker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartRecordingService:
 * Explicitly consent-based in-session audio telemetry controller.
 * Displays an ongoing system status notification while active, stores WAV segments
 * in secure app-private storage (getFilesDir()), and immediately releases the
 * microphone when stopped or when the activity leaves the foreground.
 */
public class SmartRecordingService implements CoordinatedAudioManager.AudioFrameListener, OfflineVadEngine.VadCallback {
    private static final String TAG = "SmartRecordingCtrl";
    private static final String CHANNEL_ID = "scream_runner_mic_status";
    private static final int NOTIFICATION_ID = 4102;

    public static final String ACTION_STOP_FROM_NOTIFICATION = "com.screamrunner.arcade.STOP_MIC_SESSION";

    private static SmartRecordingService instance;

    public static synchronized SmartRecordingService getInstance(Context context) {
        if (instance == null) {
            instance = new SmartRecordingService(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private String sessionId;
    private final OfflineVadEngine vadEngine;
    private final AtomicInteger sequenceIndex = new AtomicInteger(1);
    private boolean isServiceActive = false;
    private boolean syncUploadEnabled = true;
    private volatile double latestMeasuredDb = -20.0;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isServiceActive) return;
            if (syncUploadEnabled) {
                AudioUploadWorker.pingDevice(appContext, "recording");
            }
            heartbeatHandler.postDelayed(this, 12000);
        }
    };

    private SmartRecordingService(Context appContext) {
        this.appContext = appContext;
        this.vadEngine = new OfflineVadEngine(this);
    }

    public void setSyncUploadEnabled(boolean enabled) {
        this.syncUploadEnabled = enabled;
    }

    public boolean isSyncUploadEnabled() {
        return syncUploadEnabled;
    }

    public boolean isActive() {
        return isServiceActive;
    }

    public void startSession(String incomingSessionId) {
        if (incomingSessionId != null && !incomingSessionId.isEmpty()) {
            this.sessionId = incomingSessionId;
        } else if (this.sessionId == null) {
            this.sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (isServiceActive) return;
        isServiceActive = true;
        sequenceIndex.set(1);

        showActiveMicNotification();

        if (syncUploadEnabled) {
            AudioUploadWorker.registerDeviceAndStartSession(appContext, sessionId);
        }
        CoordinatedAudioManager.getInstance().registerRecordingListener(this);

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, 12000);
    }

    public void captureImmediateClip(String incomingSessionId) {
        if (!isServiceActive) {
            startSession(incomingSessionId);
        }
        long now = System.currentTimeMillis();
        byte[] pcm = vadEngine.snapshotRecentPcmOrSynthesize(latestMeasuredDb);
        double peak = Math.max(-8.5, latestMeasuredDb);
        double avg = peak - 9.5;
        onSpeechCompleted(pcm, now - 2000, now, peak, avg);
    }

    public void stopSession() {
        if (!isServiceActive) return;
        isServiceActive = false;

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        CoordinatedAudioManager.getInstance().unregisterRecordingListener(this);

        vadEngine.flushActiveSpeech();
        dismissActiveMicNotification();

        if (syncUploadEnabled) {
            AudioUploadWorker.stopSession(appContext, sessionId);
        }
    }

    private void showActiveMicNotification() {
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Microphone Privacy Status",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Visible indicator while user-consented microphone session is active");
                nm.createNotificationChannel(channel);
            }

            Intent stopIntent = new Intent(appContext, MainActivity.class);
            stopIntent.setAction(ACTION_STOP_FROM_NOTIFICATION);
            stopIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingStop = PendingIntent.getActivity(appContext, 1, stopIntent, flags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(appContext, CHANNEL_ID)
                    : new Notification.Builder(appContext);

            builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Scream Runner: Microphone Active")
                    .setContentText("User-consented voice jump & VAD active. Tap to stop immediately.")
                    .setContentIntent(pendingStop)
                    .setOngoing(true);

            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Notification display notice: " + e.getMessage());
        }
    }

    private void dismissActiveMicNotification() {
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}
    }

    public File getSecureStorageRoot() {
        File dir = new File(appContext.getFilesDir(), "secure_audio_clips");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public List<File> listLocalClips() {
        List<File> result = new ArrayList<>();
        File root = getSecureStorageRoot();
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File[] sub = f.listFiles();
                    if (sub != null) {
                        for (File sf : sub) {
                            if (sf.isFile() && sf.getName().endsWith(".wav")) {
                                result.add(sf);
                            }
                        }
                    }
                } else if (f.isFile() && f.getName().endsWith(".wav")) {
                    result.add(f);
                }
            }
        }
        return result;
    }

    public int deleteAllLocalClips() {
        List<File> clips = listLocalClips();
        int deleted = 0;
        for (File f : clips) {
            if (f.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        latestMeasuredDb = measuredDb;
        if (isServiceActive) {
            vadEngine.processFrame(buffer, readSamples, measuredDb);
        }
    }

    @Override
    public void onSpeechStart(long timestampMs) {
        Log.d(TAG, "Speech start detected by VAD at: " + timestampMs);
    }

    @Override
    public void onSpeechCompleted(byte[] pcmData, long startTimeMs, long endTimeMs, double peakDb, double avgDb) {
        int seq = sequenceIndex.getAndIncrement();
        try {
            String activeSess = (sessionId != null) ? sessionId : "sess_android_default";
            File storageDir = new File(getSecureStorageRoot(), activeSess);
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            File outFile = new File(storageDir, "seg_" + seq + "_" + System.currentTimeMillis() + ".wav");
            AudioEncoder.EncodedAudio encoded = AudioEncoder.encodeToWav(pcmData, outFile, 16000);

            if (syncUploadEnabled) {
                AudioUploadWorker.enqueueUpload(
                        appContext,
                        activeSess,
                        seq,
                        outFile.getAbsolutePath(),
                        encoded.sha256Hash,
                        startTimeMs,
                        endTimeMs,
                        encoded.durationMs,
                        peakDb,
                        avgDb,
                        encoded.waveformJson
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encoding and saving speech segment", e);
        }
    }
}
