package com.screamrunner.game.audio;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.screamrunner.game.MainActivity;
import com.screamrunner.game.upload.AudioUploadWorker;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartRecordingService:
 * Authorized Android Foreground Service for Smart Voice Activity Recording.
 * Displays a visible persistent notification with active recording duration
 * and an explicit STOP button.
 */
public class SmartRecordingService extends Service implements CoordinatedAudioManager.AudioFrameListener, OfflineVadEngine.VadCallback {
    private static final String TAG = "SmartRecordingSvc";
    private static final String CHANNEL_ID = "scream_runner_recording_channel";
    private static final int NOTIFICATION_ID = 4099;

    public static final String ACTION_START = "com.screamrunner.game.action.START_RECORDING";
    public static final String ACTION_STOP = "com.screamrunner.game.action.STOP_RECORDING";
    public static final String EXTRA_SESSION_ID = "extra_session_id";

    private String sessionId;
    private long sessionStartTimestamp;
    private PowerManager.WakeLock wakeLock;
    private OfflineVadEngine vadEngine;
    private final AtomicInteger sequenceIndex = new AtomicInteger(1);
    private boolean isServiceActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        vadEngine = new OfflineVadEngine(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "STOP action requested by user via notification or activity.");
            stopAuthorizedSession();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            if (sessionId == null) {
                sessionId = "sess_" + UUID.randomUUID().toString();
            }
            startAuthorizedSession();
        }

        return START_STICKY;
    }

    private void startAuthorizedSession() {
        if (isServiceActive) return;
        isServiceActive = true;
        sessionStartTimestamp = System.currentTimeMillis();

        // Foreground Notification with visible STOP action
        Notification notification = buildNotification("Authorized Audio Recording Active", "Listening for voice activity (VAD enabled)");
        startForeground(NOTIFICATION_ID, notification);

        // Acquire partial wake lock for authorized screen-off operation up to 6 hours
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreamRunner:RecordingWakeLock");
            wakeLock.acquire(6 * 60 * 60 * 1000L); // 6 hours limit
        }

        // Register with CoordinatedAudioManager
        CoordinatedAudioManager.getInstance().registerRecordingListener(this);
        Log.i(TAG, "Authorized recording session started: " + sessionId);
    }

    private void stopAuthorizedSession() {
        if (!isServiceActive) return;
        isServiceActive = false;

        CoordinatedAudioManager.getInstance().unregisterRecordingListener(this);

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        Log.i(TAG, "Authorized recording session stopped: " + sessionId);
    }

    @Override
    public void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        if (isServiceActive && vadEngine != null) {
            vadEngine.processFrame(buffer, readSamples, measuredDb);
        }
    }

    @Override
    public void onSpeechStart(long timestampMs) {
        Log.d(TAG, "Speech start detected by VAD at: " + timestampMs);
    }

    @Override
    public void onSpeechCompleted(byte[] pcmData, long startTimeMs, long endTimeMs, double peakDb, double avgDb) {
        if (!isServiceActive) return;

        int seq = sequenceIndex.getAndIncrement();
        try {
            File storageDir = new File(getFilesDir(), "audio_cache/" + sessionId);
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            File outFile = new File(storageDir, "seg_" + seq + "_" + System.currentTimeMillis() + ".wav");
            AudioEncoder.EncodedAudio encoded = AudioEncoder.encodeToWav(pcmData, outFile, 16000);

            Log.i(TAG, "Speech segment saved: " + outFile.getName() + " (" + encoded.durationMs + "ms, peak: " + String.format("%.1f", peakDb) + "dB)");

            // Enqueue upload
            AudioUploadWorker.enqueueUpload(
                    getApplicationContext(),
                    sessionId,
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

        } catch (Exception e) {
            Log.e(TAG, "Error encoding and saving speech segment", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                java.lang.reflect.Constructor<?> constructor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = constructor.newInstance(CHANNEL_ID, "Scream Runner Smart Recording", 2); // 2 = IMPORTANCE_LOW

                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    java.lang.reflect.Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                    createMethod.invoke(nm, channel);
                }
            } catch (Exception e) {
                Log.w(TAG, "NotificationChannel reflection setup note: " + e.getMessage());
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent stopIntent = new Intent(this, SmartRecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(
                this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = new Notification.Builder(this);
        builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(activityPendingIntent)
                .addAction(android.R.drawable.ic_delete, "STOP RECORDING", stopPendingIntent)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.lang.reflect.Method channelMethod = Notification.Builder.class.getMethod("setChannelId", String.class);
                channelMethod.invoke(builder, CHANNEL_ID);
            } catch (Exception ignored) {}
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        stopAuthorizedSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
