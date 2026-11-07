package com.samsdashboard.admin.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioPlayerController {
    private static final String TAG = "AudioPlayerController";

    public interface PlayerCallback {
        void onProgress(int currentMs, int durationMs, float progressRatio);
        void onPlaybackCompleted();
        void onError(String message);
    }

    private final Context context;
    private MediaPlayer mediaPlayer;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private PlayerCallback callback;
    private boolean isUpdatingProgress = false;
    private float currentSpeed = 1.0f;
    private volatile long playbackGeneration = 0;
    private boolean prepared = false;
    private boolean released = false;
    private File cachedFile;

    public AudioPlayerController(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void playLocalDemoTone(float speed) {
        stop();
        this.currentSpeed = speed;
        final long request = playbackGeneration;
        if (released) return;
        downloadExecutor.execute(() -> {
            try {
                File demoWav = new File(context.getCacheDir(), "local_demo_sample.wav");
                writeSynthesizedDemoWav(demoWav);
                progressHandler.post(() -> { if (request == playbackGeneration && !released) playCachedFile(demoWav); });
            } catch (Exception e) {
                postError("Demo audio error: " + e.getMessage());
            }
        });
    }

    private void writeSynthesizedDemoWav(File outFile) throws Exception {
        int sampleRate = 16000;
        int numSamples = sampleRate * 2; // 2.0 seconds
        int dataSize = numSamples * 2;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(44 + dataSize);

        bos.write(new byte[]{'R', 'I', 'F', 'F'});
        writeInt(bos, 36 + dataSize);
        bos.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeInt(bos, 16);
        writeShort(bos, (short) 1);
        writeShort(bos, (short) 1);
        writeInt(bos, sampleRate);
        writeInt(bos, sampleRate * 2);
        writeShort(bos, (short) 2);
        writeShort(bos, (short) 16);
        bos.write(new byte[]{'d', 'a', 't', 'a'});
        writeInt(bos, dataSize);

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / sampleRate;
            double env = Math.sin((i / (double) numSamples) * Math.PI);
            double wave = 0.55 * Math.sin(2 * Math.PI * 330 * t) + 0.35 * Math.sin(2 * Math.PI * 660 * t);
            short sample = (short) (24000 * env * wave);
            bos.write(sample & 0xFF);
            bos.write((sample >> 8) & 0xFF);
        }

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(bos.toByteArray());
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    public void playUrl(String streamUrl, float speed, String authToken) {
        stop();
        this.currentSpeed = speed;
        final long request = playbackGeneration;
        if (released) return;

        downloadExecutor.execute(() -> {
            File tempWav = null;
            HttpURLConnection conn = null;
            try {
                tempWav = File.createTempFile("playback_", ".audio", context.getCacheDir());
                URL url = new URL(streamUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                if (authToken != null && !authToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + authToken);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    postError("Audio stream returned HTTP " + code);
                    return;
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempWav)) {
                    byte[] buf = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = in.read(buf)) != -1) {
                        if (request != playbackGeneration || released) { tempWav.delete(); return; }
                        total += read;
                        if (total > 25L * 1024 * 1024) throw new java.io.IOException("Audio exceeds playback size limit");
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }

                final File completed = tempWav;
                progressHandler.post(() -> {
                    if (request == playbackGeneration && !released) playCachedFile(completed);
                    else completed.delete();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error downloading audio stream", e);
                if (tempWav != null) tempWav.delete();
                if (request == playbackGeneration && !released) postError("Audio fetch failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void playCachedFile(File wavFile) {
        releasePlayer();
        cachedFile = wavFile;
        try {
            mediaPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            try (FileInputStream fis = new FileInputStream(wavFile)) {
                mediaPlayer.setDataSource(fis.getFD());
            }
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                mp.start();
                setSpeed(currentSpeed);
                startProgressTracker();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                stopProgressTracker();
                if (callback != null) {
                    callback.onPlaybackCompleted();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                postError("Playback error (" + what + ")");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error playing cached WAV file", e);
            postError("Playback failed: " + e.getMessage());
        }
    }

    private void postError(String message) {
        progressHandler.post(() -> {
            if (callback != null) {
                callback.onError(message);
            }
        });
    }

    public void pause() {
        if (prepared && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void resume() {
        if (prepared && mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            startProgressTracker();
        }
    }

    public void seekTo(float progressRatio) {
        if (mediaPlayer != null) {
            try {
                int duration = mediaPlayer.getDuration();
                if (duration > 0) {
                    int seekMs = (int) (duration * Math.max(0f, Math.min(1f, progressRatio)));
                    mediaPlayer.seekTo(seekMs);
                }
            } catch (Exception ignored) {}
        }
    }

    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (mediaPlayer.isPlaying()) {
                    PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(speed);
                    mediaPlayer.setPlaybackParams(params);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to set playback speed", e);
            }
        }
    }

    public void stop() {
        playbackGeneration++;
        releasePlayer();
    }

    public void release() {
        released = true;
        stop();
        callback = null;
        downloadExecutor.shutdownNow();
    }

    private void releasePlayer() {
        prepared = false;
        stopProgressTracker();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (cachedFile != null) { cachedFile.delete(); cachedFile = null; }
    }

    private void startProgressTracker() {
        stopProgressTracker();
        isUpdatingProgress = true;
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isUpdatingProgress || mediaPlayer == null) return;
                try {
                    if (mediaPlayer.isPlaying()) {
                        int cur = mediaPlayer.getCurrentPosition();
                        int dur = mediaPlayer.getDuration();
                        float ratio = (dur > 0) ? ((float) cur / dur) : 0;
                        if (callback != null) {
                            callback.onProgress(cur, dur, ratio);
                        }
                    }
                } catch (Exception ignored) {}
                progressHandler.postDelayed(this, 80);
            }
        });
    }

    private void stopProgressTracker() {
        isUpdatingProgress = false;
        progressHandler.removeCallbacksAndMessages(null);
    }
}
