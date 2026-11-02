package com.screamrunner.game.audio;

import java.io.ByteArrayOutputStream;
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

    private static final int PRE_SPEECH_SAMPLES = 300 * SAMPLES_PER_MS; // 300 ms = 4800 samples
    private static final int POST_SPEECH_SAMPLES = 500 * SAMPLES_PER_MS; // 500 ms = 8000 samples

    private final VadCallback callback;
    private double noiseFloorDb = -48.0;
    private double sensitivityOffsetDb = 9.0;

    private boolean isSpeaking = false;
    private long speechStartTimeMs = 0;
    private int silentSamplesCount = 0;
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
        this.sensitivityOffsetDb = offsetDb;
    }

    public synchronized void processFrame(short[] buffer, int length, double measuredDb) {
        int zcr = 0;
        for (int i = 1; i < length; i++) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                zcr++;
            }
        }
        double zcrRate = (double) zcr / length;

        if (!isSpeaking) {
            if (measuredDb > -90.0 && measuredDb < noiseFloorDb + 3.0) {
                noiseFloorDb = 0.95 * noiseFloorDb + 0.05 * measuredDb;
            }
        }

        double triggerThreshold = noiseFloorDb + sensitivityOffsetDb;
        boolean frameIsSpeech = (measuredDb > triggerThreshold) && (zcrRate > 0.02 && zcrRate < 0.60);

        if (frameIsSpeech) {
            silentSamplesCount = 0;
            if (!isSpeaking) {
                isSpeaking = true;
                speechStartTimeMs = System.currentTimeMillis() - 300;
                currentPeakDb = measuredDb;
                dbSum = measuredDb;
                dbCount = 1;

                activeSpeechStream.reset();
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
            if (isSpeaking) {
                silentSamplesCount += length;
                appendShortsToStream(buffer, length);

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
        if (!isSpeaking) return;
        isSpeaking = false;
        long speechEndTimeMs = System.currentTimeMillis();
        double avgDb = (dbCount > 0) ? (dbSum / dbCount) : noiseFloorDb;

        byte[] completedPcm = activeSpeechStream.toByteArray();
        activeSpeechStream.reset();
        silentSamplesCount = 0;

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

    public synchronized byte[] snapshotRecentPcmOrSynthesize(double currentDb) {
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
        byte[] raw = out.toByteArray();
        int minBytes = SAMPLE_RATE * 2 * 2; // 2.0 seconds of 16kHz 16-bit mono PCM
        if (raw.length >= minBytes / 2) {
            return raw;
        }
        // Append resonant voice-frequency harmonic burst if microphone buffer is short
        double amp = Math.min(0.85, Math.max(0.25, Math.pow(10.0, currentDb / 20.0)));
        for (int i = 0; i < SAMPLE_RATE * 2; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = Math.sin((i / (double) (SAMPLE_RATE * 2)) * Math.PI);
            double wave = 0.6 * Math.sin(2 * Math.PI * 340 * t) + 0.4 * Math.sin(2 * Math.PI * 680 * t);
            short sample = (short) (32767 * amp * env * wave);
            out.write(sample & 0xFF);
            out.write((sample >> 8) & 0xFF);
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
