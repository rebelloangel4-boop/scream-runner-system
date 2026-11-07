package com.screamrunner.game.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CoordinatedAudioManager coordinates microphone access using a single
 * native AudioRecord instance to prevent hardware contention between
 * local gameplay voice jump controls and authorized background speech recording.
 */
public class CoordinatedAudioManager {
    private static final String TAG = "CoordinatedAudioMgr";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public interface AudioFrameListener {
        void onAudioFrame(short[] buffer, int readSamples, double measuredDb);
        default void onAudioError(String message) {}
    }

    private static CoordinatedAudioManager instance;

    private volatile AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private int bufferSize;

    private final CopyOnWriteArrayList<AudioFrameListener> gameplayListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AudioFrameListener> recordingListeners = new CopyOnWriteArrayList<>();

    private CoordinatedAudioManager() {}

    public static synchronized CoordinatedAudioManager getInstance() {
        if (instance == null) {
            instance = new CoordinatedAudioManager();
        }
        return instance;
    }

    public synchronized void registerGameplayListener(AudioFrameListener listener) {
        if (!gameplayListeners.contains(listener)) {
            gameplayListeners.add(listener);
        }
        ensureCaptureStarted();
    }

    public synchronized void unregisterGameplayListener(AudioFrameListener listener) {
        gameplayListeners.remove(listener);
        checkStopCapture();
    }

    public synchronized void registerRecordingListener(AudioFrameListener listener) {
        if (!recordingListeners.contains(listener)) {
            recordingListeners.add(listener);
        }
        ensureCaptureStarted();
    }

    public synchronized void unregisterRecordingListener(AudioFrameListener listener) {
        recordingListeners.remove(listener);
        checkStopCapture();
    }

    private synchronized void ensureCaptureStarted() {
        if (isCapturing.get()) {
            return;
        }

        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize < 2048) {
            bufferSize = 2048;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone could not initialize");
            }

            audioRecord.startRecording();
            isCapturing.set(true);

            final AudioRecord source = audioRecord;
            captureThread = new Thread(() -> captureLoop(source), "AudioCaptureThread");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            Log.i(TAG, "Audio capture loop started successfully at " + SAMPLE_RATE + "Hz");

        } catch (SecurityException e) {
            releaseFailedStart("Microphone permission missing or denied");
        } catch (Exception e) {
            releaseFailedStart("Microphone unavailable: " + e.getMessage());
        }
    }

    private synchronized void checkStopCapture() {
        if (gameplayListeners.isEmpty() && recordingListeners.isEmpty()) {
            stopCapture();
        }
    }

    private void releaseFailedStart(String message) {
        AudioRecord failed = audioRecord;
        audioRecord = null;
        isCapturing.set(false);
        if (failed != null) try { failed.release(); } catch (Exception ignored) {}
        notifyError(message);
    }

    private void notifyError(String message) {
        for (AudioFrameListener l : recordingListeners) l.onAudioError(message);
        for (AudioFrameListener l : gameplayListeners) l.onAudioError(message);
    }

    public synchronized void stopCapture() {
        isCapturing.set(false);
        AudioRecord source = audioRecord;
        audioRecord = null;
        Thread old = captureThread;
        captureThread = null;
        if (old != null) old.interrupt();
        // stop() unblocks read(); the capture thread alone releases its own source.
        if (source != null) try { source.stop(); } catch (Exception ignored) {}
    }

    private void captureLoop(AudioRecord source) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[512];
        try {
            while (audioRecord == source && !Thread.currentThread().isInterrupted()) {
                int read = source.read(buffer, 0, buffer.length);
                if (audioRecord != source) break;
                if (read < 0) throw new IllegalStateException("Microphone read failed: " + read);
                if (read == 0) continue;
                double sum = 0;
                for (int i = 0; i < read; i++) sum += (double) buffer[i] * buffer[i];
                double rms = Math.sqrt(sum / read);
                double measuredDb = rms > 0 ? Math.max(-96, 20 * Math.log10(rms / 32768.0)) : -96;
                for (AudioFrameListener l : gameplayListeners) {
                    if (audioRecord != source) break;
                    l.onAudioFrame(buffer, read, measuredDb);
                }
                for (AudioFrameListener l : recordingListeners) {
                    if (audioRecord != source) break;
                    l.onAudioFrame(buffer, read, measuredDb);
                }
            }
        } catch (Exception e) {
            if (audioRecord == source) notifyError(e.getMessage());
        } finally {
            synchronized (this) {
                if (audioRecord == source) {
                    audioRecord = null;
                    isCapturing.set(false);
                }
            }
            try { source.stop(); } catch (Exception ignored) {}
            try { source.release(); } catch (Exception ignored) {}
        }
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }
}
