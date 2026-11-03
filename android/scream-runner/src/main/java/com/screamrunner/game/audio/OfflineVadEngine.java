package com.screamrunner.game.audio;

import java.io.ByteArrayOutputStream;
import java.util.LinkedList;

/**
 * OfflineVadEngine v3.0:
 * Real-time Voice Activity Detection utilizing adaptive noise-floor calibration,
 * hysteresis energy thresholding, and Zero-Crossing Rate (ZCR).
 * Maintains an exact 300ms pre-roll circular buffer and 500ms post-roll hangover buffer
 * so the initial consonant of spoken words is never clipped.
 */
public class OfflineVadEngine {

    public interface VadCallback {
        void onSpeechStart(long timestampMs);
        void onSpeechCompleted(byte[] pcmData, long startTimeMs, long endTimeMs, double peakDb, double avgDb);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit PCM
    private static final int SAMPLES_PER_MS = SAMPLE_RATE / 1000;

    public static final int PRE_ROLL_MS = 300;
    public static final int POST_ROLL_MS = 500;
    private static final int PRE_SPEECH_SAMPLES = PRE_ROLL_MS * SAMPLES_PER_MS;   // 4,800 samples (300 ms)
    private static final int POST_SPEECH_SAMPLES = POST_ROLL_MS * SAMPLES_PER_MS; // 8,000 samples (500 ms)
    private static final int MAX_SEGMENT_SAMPLES = 15000 * SAMPLES_PER_MS;        // 15s max per segment

    private final VadCallback callback;
    private double noiseFloorDb = -48.0;
    private double sensitivityOffsetDb = 8.5;
    private int calibrationFramesRemaining = 20;

    private boolean isSpeaking = false;
    private long speechStartTimeMs = 0;
    private int silentSamplesCount = 0;
    private int activeSamplesCount = 0;
    private double currentPeakDb = -96.0;
    private double dbSum = 0;
    private int dbCount = 0;

    private final LinkedList<short[]> preSpeechRingBuffer = new LinkedList<>();
    private int ringBufferSampleCount = 0;

    private final ByteArrayOutputStream activeSpeechStream = new ByteArrayOutputStream();

    public OfflineVadEngine(VadCallback callback) {
        this.callback = callback;
    }

    public synchronized void setSensitivityOffsetDb(double offsetDb) {
        this.sensitivityOffsetDb = Math.max(4.0, Math.min(24.0, offsetDb));
    }

    public synchronized void triggerCalibration() {
        calibrationFramesRemaining = 25;
    }

    public synchronized void processFrame(short[] buffer, int length, double measuredDb) {
        if (length <= 0) return;

        int zcr = 0;
        for (int i = 1; i < length; i++) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                zcr++;
            }
        }
        double zcrRate = (double) zcr / length;

        // Adaptive noise-floor calibration during initial frames or ambient silence
        if (calibrationFramesRemaining > 0 && measuredDb > -90.0 && measuredDb < -20.0) {
            noiseFloorDb = 0.75 * noiseFloorDb + 0.25 * measuredDb;
            calibrationFramesRemaining--;
        } else if (!isSpeaking && measuredDb > -90.0 && measuredDb < noiseFloorDb + 3.5) {
            noiseFloorDb = 0.96 * noiseFloorDb + 0.04 * measuredDb;
        }
        noiseFloorDb = Math.max(-68.0, Math.min(-28.0, noiseFloorDb));

        double onsetThreshold = noiseFloorDb + sensitivityOffsetDb;
        double sustainThreshold = onsetThreshold - 3.0; // Hysteresis prevents mid-word dropouts
        double activeThreshold = isSpeaking ? sustainThreshold : onsetThreshold;

        boolean frameIsSpeech = (measuredDb > activeThreshold) && (zcrRate > 0.015 && zcrRate < 0.65);

        if (frameIsSpeech) {
            silentSamplesCount = 0;
            if (!isSpeaking) {
                isSpeaking = true;
                speechStartTimeMs = System.currentTimeMillis() - PRE_ROLL_MS;
                currentPeakDb = measuredDb;
                dbSum = measuredDb;
                dbCount = 1;
                activeSamplesCount = 0;

                activeSpeechStream.reset();
                // Prepend 300ms pre-roll ring buffer so initial consonant is never clipped
                for (short[] preBuf : preSpeechRingBuffer) {
                    appendShortsToStream(preBuf, preBuf.length);
                    activeSamplesCount += preBuf.length;
                }
                preSpeechRingBuffer.clear();
                ringBufferSampleCount = 0;

                if (callback != null) {
                    callback.onSpeechStart(speechStartTimeMs);
                }
            } else {
                if (measuredDb > currentPeakDb) {
                    currentPeakDb = measuredDb;
                }
                dbSum += measuredDb;
                dbCount++;
            }

            appendShortsToStream(buffer, length);
            activeSamplesCount += length;

            if (activeSamplesCount >= MAX_SEGMENT_SAMPLES) {
                flushActiveSpeech();
            }

        } else {
            if (isSpeaking) {
                silentSamplesCount += length;
                appendShortsToStream(buffer, length);
                activeSamplesCount += length;

                // 500ms post-roll hangover completed
                if (silentSamplesCount >= POST_SPEECH_SAMPLES) {
                    flushActiveSpeech();
                }
            } else {
                short[] copy = new short[length];
                System.arraycopy(buffer, 0, copy, 0, length);
                preSpeechRingBuffer.addLast(copy);
                ringBufferSampleCount += length;

                while (ringBufferSampleCount > PRE_SPEECH_SAMPLES && !preSpeechRingBuffer.isEmpty()) {
                    short[] removed = preSpeechRingBuffer.removeFirst();
                    ringBufferSampleCount -= removed.length;
                }
            }
        }
    }

    public synchronized void flushActiveSpeech() {
        if (!isSpeaking && activeSpeechStream.size() == 0) return;
        isSpeaking = false;
        long speechEndTimeMs = System.currentTimeMillis();
        double avgDb = (dbCount > 0) ? (dbSum / dbCount) : noiseFloorDb;

        byte[] completedPcm = activeSpeechStream.toByteArray();
        activeSpeechStream.reset();
        silentSamplesCount = 0;
        activeSamplesCount = 0;

        if (completedPcm.length >= (250 * SAMPLES_PER_MS * BYTES_PER_SAMPLE)) {
            if (callback != null) {
                callback.onSpeechCompleted(
                        completedPcm,
                        speechStartTimeMs,
                        speechEndTimeMs,
                        currentPeakDb,
                        avgDb
                );
            }
        }
    }

    public synchronized byte[] captureCurrentBufferedPcm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (short[] frame : preSpeechRingBuffer) {
            for (short s : frame) {
                out.write(s & 0xFF);
                out.write((s >> 8) & 0xFF);
            }
        }
        if (activeSpeechStream.size() > 0) {
            try {
                activeSpeechStream.writeTo(out);
            } catch (Exception ignored) {}
        }
        return out.toByteArray();
    }

    private void appendShortsToStream(short[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            short s = buffer[i];
            activeSpeechStream.write(s & 0xFF);
            activeSpeechStream.write((s >> 8) & 0xFF);
        }
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public double getNoiseFloorDb() {
        return noiseFloorDb;
    }
}
