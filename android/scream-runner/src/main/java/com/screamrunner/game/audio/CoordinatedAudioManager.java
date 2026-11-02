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
    }

    private static CoordinatedAudioManager instance;

    private AudioRecord audioRecord;
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
                Log.e(TAG, "AudioRecord failed to initialize.");
                return;
            }

            audioRecord.startRecording();
            isCapturing.set(true);

            captureThread = new Thread(this::captureLoop, "AudioCaptureThread");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            Log.i(TAG, "Audio capture loop started successfully at " + SAMPLE_RATE + "Hz");

        } catch (SecurityException e) {
            Log.e(TAG, "RECORD_AUDIO permission missing or denied.", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting AudioRecord capture.", e);
        }
    }

    private synchronized void checkStopCapture() {
        if (gameplayListeners.isEmpty() && recordingListeners.isEmpty()) {
            stopCapture();
        }
    }

    public synchronized void stopCapture() {
        isCapturing.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }
        Log.i(TAG, "Audio capture stopped and microphone released.");
    }

    private void captureLoop() {
        short[] buffer = new short[512]; // ~32ms frame at 16kHz

        while (isCapturing.get() && !Thread.currentThread().isInterrupted()) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read > 0) {
                // Calculate real measured RMS decibel
                double sum = 0;
                for (int i = 0; i < read; i++) {
                    sum += buffer[i] * buffer[i];
                }
                double rms = Math.sqrt(sum / read);
                // Standard audio dBFS reference: 20 * log10(rms / 32768)
                double measuredDb = (rms > 1) ? (20.0 * Math.log10(rms / 32767.0)) : -96.0;

                // Dispatch to local gameplay listeners
                for (AudioFrameListener l : gameplayListeners) {
                    try {
                        l.onAudioFrame(buffer, read, measuredDb);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in gameplay listener", e);
                    }
                }

                // Dispatch to authorized recording VAD listeners
                for (AudioFrameListener l : recordingListeners) {
                    try {
                        l.onAudioFrame(buffer, read, measuredDb);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in recording listener", e);
                    }
                }
            } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
        }
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }
}
