package com.samsdashboard.admin.player;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class AudioPlayerController {
    private static final String TAG = "AudioPlayerController";

    public interface PlayerCallback {
        void onProgress(int currentMs, int durationMs, float progressRatio);
        void onPlaybackCompleted();
        void onError(String message);
    }

    private MediaPlayer mediaPlayer;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private PlayerCallback callback;
    private boolean isUpdatingProgress = false;
    private float currentSpeed = 1.0f;

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void playUrl(String streamUrl, float speed) {
        stop();
        this.currentSpeed = speed;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(streamUrl);
            mediaPlayer.setOnPreparedListener(mp -> {
                setSpeed(currentSpeed);
                mp.start();
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
                if (callback != null) {
                    callback.onError("Playback error (" + what + ")");
                }
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error playing audio stream", e);
            if (callback != null) {
                callback.onError(e.getMessage());
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            startProgressTracker();
        }
    }

    public void seekTo(float progressRatio) {
        if (mediaPlayer != null) {
            int duration = mediaPlayer.getDuration();
            if (duration > 0) {
                int seekMs = (int) (duration * progressRatio);
                mediaPlayer.seekTo(seekMs);
            }
        }
    }

    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set playback speed", e);
            }
        }
    }

    public void stop() {
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
    }

    private void startProgressTracker() {
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
                progressHandler.postDelayed(this, 100);
            }
        });
    }

    private void stopProgressTracker() {
        isUpdatingProgress = false;
        progressHandler.removeCallbacksAndMessages(null);
    }
}
