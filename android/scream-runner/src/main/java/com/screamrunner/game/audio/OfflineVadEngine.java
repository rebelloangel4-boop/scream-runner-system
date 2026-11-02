package com.screamrunner.game.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;

/**
 * OfflineVadEngine:
 * Real-time Voice Activity Detection utilizing adaptive energy thresholding
 * and Zero-Crossing Rate (ZCR).
 * Maintains a 300ms pre-speech circular buffer and a 500ms post-speech hangover buffer.
 */
public class OfflineVadEngine {

    public interface VadCallback {
        void onSpeechStart(long timestampMs);
        void onSpeechCompleted(byte[] pcmData, long startTimeMs, long endTimeMs, double peakDb, double avgDb);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit
    private static final int SAMPLES_PER_MS = SAMPLE_RATE / 1000;

    // Buffer durations in samples
    private static final int PRE_SPEECH_SAMPLES = 300 * SAMPLES_PER_MS; // 300 ms = 4800 samples
    private static final int POST_SPEECH_SAMPLES = 500 * SAMPLES_PER_MS; // 500 ms = 8000 samples

    private final VadCallback callback;
    private double noiseFloorDb = -50.0;
    private double sensitivityOffsetDb = 10.0; // Trigger threshold = noiseFloor + sensitivityOffset

    private boolean isSpeaking = false;
    private long speechStartTimeMs = 0;
    private int silentSamplesCount = 0;
    private double currentPeakDb = -96.0;
    private double dbSum = 0;
    private int dbCount = 0;

    // Ring buffer for pre-speech frames (300 ms)
    private final LinkedList<short[]> preSpeechRingBuffer = new LinkedList<>();
    private int ringBufferSampleCount = 0;

    // Active speech buffer
    private final ByteArrayOutputStream activeSpeechStream = new ByteArrayOutputStream();

    public OfflineVadEngine(VadCallback callback) {
        this.callback = callback;
    }

    public synchronized void setSensitivityOffsetDb(double offsetDb) {
        this.sensitivityOffsetDb = offsetDb;
    }

    public synchronized void processFrame(short[] buffer, int length, double measuredDb) {
        // Zero-Crossing Rate calculation
        int zcr = 0;
        for (int i = 1; i < length; i++) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                zcr++;
            }
        }
        double zcrRate = (double) zcr / length;

        // Dynamic noise floor adaptation (slow leak towards ambient background during silence)
        if (!isSpeaking) {
            if (measuredDb > -90.0 && measuredDb < noiseFloorDb + 3.0) {
                noiseFloorDb = 0.95 * noiseFloorDb + 0.05 * measuredDb;
            }
        }

        double triggerThreshold = noiseFloorDb + sensitivityOffsetDb;
        // Human speech typically has moderate ZCR (0.05 to 0.45) and energy > threshold
        boolean frameIsSpeech = (measuredDb > triggerThreshold) && (zcrRate > 0.04 && zcrRate < 0.50);

        if (frameIsSpeech) {
            silentSamplesCount = 0;
            if (!isSpeaking) {
                // Speech onset detected!
                isSpeaking = true;
                speechStartTimeMs = System.currentTimeMillis() - 300;
                currentPeakDb = measuredDb;
                dbSum = measuredDb;
                dbCount = 1;

                activeSpeechStream.reset();
                // Flush 300 ms pre-speech ring buffer into active stream
                for (short[] preBuf : preSpeechRingBuffer) {
                    appendShortsToStream(preBuf, preBuf.length);
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

        } else {
            // Frame is silent / below threshold
            if (isSpeaking) {
                silentSamplesCount += length;
                appendShortsToStream(buffer, length);

                if (silentSamplesCount >= POST_SPEECH_SAMPLES) {
                    // Speech segment ended after 500 ms post-speech hangover
                    isSpeaking = false;
                    long speechEndTimeMs = System.currentTimeMillis();
                    double avgDb = (dbCount > 0) ? (dbSum / dbCount) : noiseFloorDb;

                    byte[] completedPcm = activeSpeechStream.toByteArray();
                    activeSpeechStream.reset();

                    // Only deliver if longer than 350 ms to eliminate tiny transients
                    if (completedPcm.length >= (350 * SAMPLES_PER_MS * BYTES_PER_SAMPLE)) {
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
                    silentSamplesCount = 0;
                }
            } else {
                // Maintain pre-speech ring buffer (last 300 ms)
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
