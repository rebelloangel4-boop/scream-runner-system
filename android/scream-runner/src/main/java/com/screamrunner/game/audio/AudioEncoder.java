package com.screamrunner.game.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * AudioEncoder supports hardware-accelerated AAC/M4A encoding (MediaCodec + MediaMuxer)
 * with lossless PCM WAV fallback, cryptographic SHA-256 checksums, and 50-bar RMS waveforms.
 */
public class AudioEncoder {
    private static final String TAG = "AudioEncoder";

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

    public static EncodedAudio encodeToM4aOrWav(byte[] pcmData, File storageDir, String baseName, int sampleRate) throws IOException {
        File m4aFile = new File(storageDir, baseName + ".m4a");
        try {
            if (encodePcmToM4a(pcmData, m4aFile, sampleRate)) {
                String sha256 = calculateFileSha256(m4aFile);
                int durationMs = (int) (((long) pcmData.length * 1000L) / (sampleRate * 2L));
                String waveformJson = calculateWaveformJson(pcmData, 50);
                return new EncodedAudio(m4aFile, sha256, durationMs, waveformJson);
            }
        } catch (Exception e) {
            Log.w(TAG, "Hardware AAC/M4A muxer unavailable, using lossless WAV fallback: " + e.getMessage());
            if (m4aFile.exists()) {
                m4aFile.delete();
            }
        }
        File wavFile = new File(storageDir, baseName + ".wav");
        return encodeToWav(pcmData, wavFile, sampleRate);
    }

    private static boolean encodePcmToM4a(byte[] pcmData, File outputFile, int sampleRate) throws Exception {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            int offset = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            int trackIndex = -1;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int remaining = pcmData.length - offset;
                            if (remaining <= 0) {
                                long ptsUs = (offset * 1000000L) / (sampleRate * 2L);
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                int chunk = Math.min(inputBuffer.remaining(), remaining);
                                inputBuffer.put(pcmData, offset, chunk);
                                long ptsUs = (offset * 1000000L) / (sampleRate * 2L);
                                codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0);
                                offset += chunk;
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outIndex >= 0) {
                    ByteBuffer encodedData = codec.getOutputBuffer(outIndex);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && muxerStarted && encodedData != null) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            return outputFile.exists() && outputFile.length() > 128;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
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
        header.write(new byte[]{'R', 'I', 'F', 'F'});
        writeInt(header, chunkSize);
        header.write(new byte[]{'W', 'A', 'V', 'E'});
        header.write(new byte[]{'f', 'm', 't', ' '});
        writeInt(header, 16);
        writeShort(header, (short) 1);
        writeShort(header, (short) channels);
        writeInt(header, sampleRate);
        writeInt(header, byteRate);
        writeShort(header, (short) blockAlign);
        writeShort(header, (short) bitsPerSample);
        header.write(new byte[]{'d', 'a', 't', 'a'});
        writeInt(header, dataSize);

        byte[] headerBytes = header.toByteArray();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(headerBytes);
            fos.write(pcmData);
        }

        String sha256 = calculateSha256(headerBytes, pcmData);
        int durationMs = (int) (((long) dataSize * 1000) / byteRate);
        String waveformJson = calculateWaveformJson(pcmData, 50);

        return new EncodedAudio(outputFile, sha256, durationMs, waveformJson);
    }

    private static String calculateFileSha256(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash_fallback_" + System.currentTimeMillis();
        }
    }

    private static String calculateSha256(byte[] header, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(header);
            md.update(data);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
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
            sb.append(String.format(Locale.US, "%.3f", norm));
            if (bar < numBars - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
