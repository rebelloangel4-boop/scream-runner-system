package com.screamrunner.game;
import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public class RegressionInstrumentation extends Instrumentation {
    private int passed = 0;
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            byte[] tail = new byte[102]; tail[100] = (byte)255; tail[101] = 127;
            org.json.JSONArray bars = new org.json.JSONArray(com.screamrunner.game.audio.AudioEncoder.calculateWaveformJson(tail, 50));
            check(bars.getDouble(49) > .7, "waveform tail must be included");
            byte[] pcm = new byte[32000];
            for (int i=0;i<16000;i++) { short v=(short)(12000*Math.sin(2*Math.PI*440*i/16000)); pcm[i*2]=(byte)v; pcm[i*2+1]=(byte)(v>>8); }
            java.io.File dir = new java.io.File(getTargetContext().getCacheDir(), "regression"); dir.mkdirs();
            com.screamrunner.game.audio.AudioEncoder.EncodedAudio audio = com.screamrunner.game.audio.AudioEncoder.encodeToM4aOrWav(pcm, dir, "synthetic", 16000);
            check(audio.file.length()>128, "encoded bytes");
            check(audio.durationMs==1000, "PCM duration");
            check(audio.sha256Hash.matches("[a-f0-9]{64}"), "SHA256");
            android.media.MediaMetadataRetriever reader = new android.media.MediaMetadataRetriever();
            reader.setDataSource(audio.file.getAbsolutePath());
            int duration = Integer.parseInt(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)); reader.release();
            check(duration>=900 && duration<=1300, "decodable encoded duration");
            int before=com.screamrunner.game.upload.AudioUploadWorker.getPendingQueueSize(getTargetContext());
            com.screamrunner.game.upload.AudioUploadWorker.enqueueUpload(getTargetContext(), "unapproved_test", 1, audio.file.getAbsolutePath(), audio.sha256Hash, 0, 1000, 1000, -8, -12, audio.waveformJson);
            check(before==com.screamrunner.game.upload.AudioUploadWorker.getPendingQueueSize(getTargetContext()), "unapproved audio must not queue");
            Intent launch=new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(launch); waitForIdleSync();
            check(activity!=null, "game launched");
            runOnMainSync(() -> activity.finish());
            audio.file.delete();
            result.putString("stream", "PASS " + passed + " executed Android regression checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + passed + " checks: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
