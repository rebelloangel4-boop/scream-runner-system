package com.screamrunner.game.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * AudioEncoder encodes raw PCM 16kHz audio into standard formats,
 * computes cryptographic SHA-256 checksums, and calculates normalized waveform bars.
 */
public class AudioEncoder {

    public static class EncodedAudio {
        public final File file;
        public final String sha256Hash;
        public final int durationMs;
        public final String waveformJson;

        public EncodedAudio(File file, String sha256Hash, int durationMs, String waveformJson) {
            this.file = file;
            this.sha256Hash = sha256Hash;
            this.durationMs = durationMs;
            this.waveformJson = waveformJson;
        }
    }

    public static EncodedAudio encodeToWav(byte[] pcmData, File outputFile, int sampleRate) throws IOException {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int dataSize = pcmData.length;
        int chunkSize = 36 + dataSize;

        ByteArrayOutputStream header = new ByteArrayOutputStream(44);
        // RIFF header
        header.write(new byte[]{'R', 'I', 'F', 'F'});
        writeInt(header, chunkSize);
        header.write(new byte[]{'W', 'A', 'V', 'E'});
        header.write(new byte[]{'f', 'm', 't', ' '});
        writeInt(header, 16); // SubChunk1Size (16 for PCM)
        writeShort(header, (short) 1); // AudioFormat 1 = PCM
        writeShort(header, (short) channels);
        writeInt(header, sampleRate);
        writeInt(header, byteRate);
        writeShort(header, (short) blockAlign);
        writeShort(header, (short) bitsPerSample);
        header.write(new byte[]{'d', 'a', 't', 'a'});
        writeInt(header, dataSize);

        byte[] headerBytes = header.toByteArray();

        // Write file
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(headerBytes);
            fos.write(pcmData);
        }

        // SHA-256 calculation
        String sha256 = calculateSha256(headerBytes, pcmData);
        int durationMs = (int) (((long) dataSize * 1000) / byteRate);
        String waveformJson = calculateWaveformJson(pcmData, 50);

        return new EncodedAudio(outputFile, sha256, durationMs, waveformJson);
    }

    private static String calculateSha256(byte[] header, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(header);
            md.update(data);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash_fallback_" + System.currentTimeMillis();
        }
    }

    public static String calculateWaveformJson(byte[] pcmData, int numBars) {
        int totalSamples = pcmData.length / 2;
        if (totalSamples == 0) return "[]";

        int samplesPerBar = Math.max(1, totalSamples / numBars);
        StringBuilder sb = new StringBuilder("[");

        for (int bar = 0; bar < numBars; bar++) {
            int start = bar * samplesPerBar;
            int end = Math.min(start + samplesPerBar, totalSamples);
            double sumSq = 0;
            int count = 0;

            for (int i = start; i < end; i++) {
                int low = pcmData[i * 2] & 0xFF;
                int high = pcmData[i * 2 + 1];
                short sample = (short) ((high << 8) | low);
                sumSq += sample * sample;
                count++;
            }

            double rms = (count > 0) ? Math.sqrt(sumSq / count) : 0;
            double norm = Math.min(1.0, Math.max(0.04, rms / 32768.0));
            sb.append(String.format(java.util.Locale.US, "%.3f", norm));
            if (bar < numBars - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
