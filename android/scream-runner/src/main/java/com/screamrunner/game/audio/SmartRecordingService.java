package com.screamrunner.game.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.screamrunner.game.MainActivity;
import com.screamrunner.game.upload.AudioUploadWorker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartRecordingService v3.0:
 * Android 14–16 compliant Foreground Microphone Service (foregroundServiceType="microphone").
 * Explicitly consent-based, displays a persistent notification with a 1-tap STOP action,
 * tracks audio focus interruptions, encodes AAC/M4A + WAV with 300ms pre-roll / 500ms post-roll,
 * and supports user-controlled local retention and deletion.
 */
public class SmartRecordingService extends Service implements CoordinatedAudioManager.AudioFrameListener, OfflineVadEngine.VadCallback {
    private static final String TAG = "SmartRecordingService";
    private static final String CHANNEL_ID = "scream_runner_mic_status_v3";
    private static final int NOTIFICATION_ID = 4103;
    private static final String PREFS_RETENTION = "scream_runner_retention_prefs";
    private static final String KEY_RETENTION_DAYS = "local_retention_days";

    public static final String ACTION_START_SESSION = "com.screamrunner.arcade.START_MIC_SESSION";
    public static final String ACTION_STOP_FROM_NOTIFICATION = "com.screamrunner.arcade.STOP_MIC_SESSION";
    public static final String EXTRA_SESSION_ID = "extra_session_id";
    public static final String EXTRA_SYNC_ENABLED = "extra_sync_enabled";

    private static volatile SmartRecordingService instance;

    public static synchronized SmartRecordingService getInstance(Context context) {
        if (instance == null) {
            instance = new SmartRecordingService(context.getApplicationContext());
        }
        return instance;
    }

    private Context appContext;
    private String sessionId;
    private OfflineVadEngine vadEngine;
    private final AtomicInteger sequenceIndex = new AtomicInteger(1);
    private volatile boolean isServiceActive = false;
    private final java.io.ByteArrayOutputStream fullRecordingPcm = new java.io.ByteArrayOutputStream();
    private long fullSegmentStartedAtMs;
    private final java.util.concurrent.ExecutorService encodingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable sessionLimit = () -> {
        stopSession();
        CoordinatedAudioManager.getInstance().stopCapture();
        stopSelf();
    };
    // Recording permission never grants sharing permission. Each saved clip is reviewed separately.
    private boolean syncUploadEnabled = false;
    private volatile double latestMeasuredDb = -60.0;
    private long interruptionStartedAtMs = 0;

    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (!isServiceActive) return;
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            interruptionStartedAtMs = System.currentTimeMillis();
            if (vadEngine != null) {
                vadEngine.flushActiveSpeech();
            }
            if (syncUploadEnabled && sessionId != null) {
                AudioUploadWorker.reportInterruption(appContext, sessionId, "Audio focus lost (incoming call or system audio interruption)", 500);
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && interruptionStartedAtMs > 0) {
            int gapMs = (int) Math.max(100, System.currentTimeMillis() - interruptionStartedAtMs);
            interruptionStartedAtMs = 0;
            if (syncUploadEnabled && sessionId != null) {
                AudioUploadWorker.reportInterruption(appContext, sessionId, "Audio focus restored after interruption", gapMs);
            }
        }
    };

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isServiceActive) return;
            if (syncUploadEnabled && appContext != null) {
                AudioUploadWorker.pingDevice(appContext, "recording");
                AudioUploadWorker.flushPendingQueue(appContext);
            }
            heartbeatHandler.postDelayed(this, 12000);
        }
    };

    public SmartRecordingService() {
        this.vadEngine = new OfflineVadEngine(this);
    }

    private SmartRecordingService(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadEngine = new OfflineVadEngine(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.appContext = getApplicationContext();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (appContext == null) {
            appContext = getApplicationContext();
        }
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_FROM_NOTIFICATION.equals(action)) {
                stopSession();
                CoordinatedAudioManager.getInstance().stopCapture();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!ACTION_START_SESSION.equals(action)) { stopSelf(); return START_NOT_STICKY; }
            String incomingId = intent.getStringExtra(EXTRA_SESSION_ID);
            if (intent.hasExtra(EXTRA_SYNC_ENABLED)) {
                syncUploadEnabled = false;
            }
            Notification notif = buildForegroundNotification();
            if (notif != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    } else {
                        startForeground(NOTIFICATION_ID, notif);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Foreground service promotion notice: " + e.getMessage());
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else {
                stopSelf();
                return START_NOT_STICKY;
            }
            startSessionInternal(incomingId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopSession();
        encodingExecutor.shutdown();
        instance = null;
        super.onDestroy();
    }

    public void setSyncUploadEnabled(boolean enabled) {
        this.syncUploadEnabled = false;
    }

    public boolean isSyncUploadEnabled() {
        return syncUploadEnabled;
    }

    public boolean isActive() {
        return isServiceActive;
    }

    public OfflineVadEngine getVadEngine() {
        return vadEngine;
    }

    public void startSession(String incomingSessionId) {
        if (appContext != null) {
            try {
                Intent serviceIntent = new Intent(appContext, SmartRecordingService.class);
                serviceIntent.setAction(ACTION_START_SESSION);
                serviceIntent.putExtra(EXTRA_SESSION_ID, incomingSessionId);
                serviceIntent.putExtra(EXTRA_SYNC_ENABLED, syncUploadEnabled);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent);
                } else {
                    appContext.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.w(TAG, "Direct session start fallback: " + e.getMessage());
            }
        }
        // Only Android-managed onStartCommand may start recording, after startForeground succeeds.
    }

    private void startSessionInternal(String incomingSessionId) {
        if (isServiceActive) return;
        if (incomingSessionId != null && !incomingSessionId.isEmpty()) {
            this.sessionId = incomingSessionId;
        } else if (this.sessionId == null) {
            this.sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (isServiceActive) return;
        isServiceActive = true;
        sequenceIndex.set(1);
        fullSegmentStartedAtMs = System.currentTimeMillis();
        heartbeatHandler.postDelayed(sessionLimit, 6L * 60 * 60 * 1000);
        vadEngine.triggerCalibration();

        enforceLocalRetentionPolicy();
        showActiveMicNotification();

        if (appContext != null) {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                try {
                    audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                } catch (Exception ignored) {}
            }
            if (syncUploadEnabled) {
                AudioUploadWorker.registerDeviceAndStartSession(appContext, sessionId);
            }
        }
        CoordinatedAudioManager.getInstance().registerRecordingListener(this);

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, 12000);
    }

    public boolean captureImmediateClip(String incomingSessionId) {
        if (!isServiceActive) {
            startSession(incomingSessionId);
        }
        long now = System.currentTimeMillis();
        byte[] pcm = vadEngine.captureCurrentBufferedPcm();
        if (pcm == null || pcm.length < 1600) {
            return false;
        }
        double peak = latestMeasuredDb;
        double avg = latestMeasuredDb;
        onSpeechCompleted(pcm, now - 1200, now, peak, avg);
        return true;
    }

    public synchronized void stopSession() {
        if (!isServiceActive) return;
        isServiceActive = false;

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.removeCallbacks(sessionLimit);
        CoordinatedAudioManager.getInstance().unregisterRecordingListener(this);
        flushFullRecording();

        if (audioManager != null) {
            try {
                audioManager.abandonAudioFocus(focusChangeListener);
            } catch (Exception ignored) {}
        }

        vadEngine.flushActiveSpeech();
        dismissActiveMicNotification();

        if (syncUploadEnabled && appContext != null) {
            AudioUploadWorker.stopSession(appContext, sessionId);
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
    }

    @Override
    public void onAudioError(String message) {
        heartbeatHandler.post(() -> {
            stopSession();
            CoordinatedAudioManager.getInstance().stopCapture();
            android.widget.Toast.makeText(appContext, "Recording stopped: " + message, android.widget.Toast.LENGTH_LONG).show();
            stopSelf();
        });
    }

    private Notification buildForegroundNotification() {
        if (appContext == null) return null;
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Authorized Microphone Telemetry",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Visible indicator while user-consented microphone session is active");
                nm.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(appContext, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Intent stopServiceIntent = new Intent(appContext, SmartRecordingService.class);
            stopServiceIntent.setAction(ACTION_STOP_FROM_NOTIFICATION);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingOpen = PendingIntent.getActivity(appContext, 10, openIntent, flags);
            PendingIntent pendingStop = PendingIntent.getService(appContext, 11, stopServiceIntent, flags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(appContext, CHANNEL_ID)
                    : new Notification.Builder(appContext);

            builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Scream Runner: Microphone Active")
                    .setContentText("Recording locally (maximum 6 hours). Tap STOP to end; review before sharing.")
                    .setContentIntent(pendingOpen)
                    .addAction(android.R.drawable.ic_media_pause, "STOP RECORDING", pendingStop)
                    .setOngoing(true);

            return builder.build();
        } catch (Exception e) {
            Log.w(TAG, "Notification builder notice: " + e.getMessage());
            return null;
        }
    }

    private void showActiveMicNotification() {
        if (appContext == null) return;
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification notif = buildForegroundNotification();
            if (nm != null && notif != null) {
                nm.notify(NOTIFICATION_ID, notif);
            }
        } catch (Exception ignored) {}
    }

    private void dismissActiveMicNotification() {
        if (appContext == null) return;
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

    public void setRetentionDays(int days) {
        if (appContext == null) return;
        appContext.getSharedPreferences(PREFS_RETENTION, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RETENTION_DAYS, Math.max(1, Math.min(90, days)))
                .apply();
        enforceLocalRetentionPolicy();
    }

    public int getRetentionDays() {
        if (appContext == null) return 7;
        return appContext.getSharedPreferences(PREFS_RETENTION, Context.MODE_PRIVATE)
                .getInt(KEY_RETENTION_DAYS, 7);
    }

    public int enforceLocalRetentionPolicy() {
        int days = getRetentionDays();
        long cutoffMs = System.currentTimeMillis() - (days * 86400000L);
        List<File> clips = listLocalClips();
        int removed = 0;
        for (File f : clips) {
            if (f.lastModified() > 0 && f.lastModified() < cutoffMs) {
                AudioUploadWorker.revokeShare(appContext, f.getAbsolutePath());
                if (f.delete()) {
                    new File(f.getAbsolutePath() + ".json").delete();
                    removed++;
                }
            }
        }
        return removed;
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
                            if (sf.isFile() && (sf.getName().endsWith(".wav") || sf.getName().endsWith(".m4a"))) {
                                result.add(sf);
                            }
                        }
                    }
                } else if (f.isFile() && (f.getName().endsWith(".wav") || f.getName().endsWith(".m4a"))) {
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
            AudioUploadWorker.revokeShare(appContext, f.getAbsolutePath());
            if (f.delete()) {
                new File(f.getAbsolutePath() + ".json").delete();
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public synchronized void onAudioFrame(short[] buffer, int readSamples, double measuredDb) {
        latestMeasuredDb = measuredDb;
        if (isServiceActive) {
            synchronized (fullRecordingPcm) {
                for (int i = 0; i < readSamples; i++) {
                    fullRecordingPcm.write(buffer[i] & 255);
                    fullRecordingPcm.write((buffer[i] >> 8) & 255);
                }
                if (fullRecordingPcm.size() >= 16000 * 2 * 30) flushFullRecording();
            }
            vadEngine.processFrame(buffer, readSamples, measuredDb);
        }
    }

    private void flushFullRecording() {
        synchronized (fullRecordingPcm) {
            if (fullRecordingPcm.size() == 0) return;
            byte[] pcm = fullRecordingPcm.toByteArray();
            long start = fullSegmentStartedAtMs;
            long end = start + pcm.length * 1000L / 32000;
            fullRecordingPcm.reset();
            fullSegmentStartedAtMs = end;
            queueEncoding(pcm, start, end, "full_");
        }
    }

    @Override
    public void onSpeechStart(long timestampMs) {
        Log.d(TAG, "Speech start detected by VAD at: " + timestampMs);
    }

    @Override
    public void onSpeechCompleted(byte[] pcmData, long startTimeMs, long endTimeMs, double peakDb, double avgDb) {
        queueEncoding(pcmData, startTimeMs, endTimeMs, "speech_");
    }

    private void queueEncoding(byte[] pcmData, long startTimeMs, long endTimeMs, String kind) {
        int seq = sequenceIndex.getAndIncrement();
        String savedSessionId = sessionId;
        encodingExecutor.execute(() -> saveEncodedSegment(pcmData, startTimeMs, endTimeMs, seq, kind, savedSessionId));
    }

    private void saveEncodedSegment(byte[] pcmData, long startTimeMs, long endTimeMs, int seq, String kind, String savedSessionId) {
        double peakDb, avgDb;
        double sumSquares = 0; int peakAmplitude = 0;
        for (int i = 0; i + 1 < pcmData.length; i += 2) {
            short value = (short) ((pcmData[i] & 255) | (pcmData[i + 1] << 8));
            peakAmplitude = Math.max(peakAmplitude, Math.abs((int) value));
            sumSquares += (double) value * value;
        }
        peakDb = peakAmplitude == 0 ? -96 : 20 * Math.log10(peakAmplitude / 32768.0);
        double rms = Math.sqrt(sumSquares / Math.max(1, pcmData.length / 2));
        avgDb = rms == 0 ? -96 : Math.max(-96, 20 * Math.log10(rms / 32768.0));
        try {
            String activeSess = (savedSessionId != null) ? savedSessionId : "sess_android_default";
            File storageDir = new File(getSecureStorageRoot(), activeSess);
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            String baseName = kind + seq + "_" + startTimeMs;
            AudioEncoder.EncodedAudio encoded = AudioEncoder.encodeToM4aOrWav(pcmData, storageDir, baseName, 16000);

            if (appContext != null) {
                AudioUploadWorker.saveLocalRecording(
                        appContext,
                        activeSess,
                        seq,
                        encoded.file.getAbsolutePath(),
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
