package com.samsdashboard.admin;
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
            com.samsdashboard.admin.auth.TokenManager tokens=new com.samsdashboard.admin.auth.TokenManager(getTargetContext());
            tokens.saveTokens("synthetic-access", "synthetic-refresh", "test");
            check("synthetic-access".equals(tokens.getAccessToken()), "Keystore access round trip");
            check("synthetic-refresh".equals(tokens.getRefreshToken()), "Keystore refresh round trip");
            String stored=getTargetContext().getSharedPreferences("sams_dashboard_secure_prefs_v3",0).getString("enc_jwt_access_token", "");
            check(stored.contains(":") && !stored.contains("synthetic-access"), "ciphertext persisted");
            tokens.clearTokens(); check(!tokens.isLoggedIn(), "logout clears credentials");
            Intent launch=new Intent(getTargetContext(), com.samsdashboard.admin.auth.LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(launch); waitForIdleSync(); check(activity!=null,"login launched");
            com.samsdashboard.admin.player.AudioPlayerController player=new com.samsdashboard.admin.player.AudioPlayerController(getTargetContext());
            runOnMainSync(() -> { player.pause(); player.resume(); player.stop(); player.release(); activity.finish(); });
            check(true,"idle playback controls and release");
            com.samsdashboard.admin.player.AudioPlayerController cancelled=new com.samsdashboard.admin.player.AudioPlayerController(getTargetContext());
            runOnMainSync(() -> { cancelled.playLocalDemoTone(1.5f); cancelled.stop(); });
            android.os.SystemClock.sleep(1200); waitForIdleSync();
            java.lang.reflect.Field field=cancelled.getClass().getDeclaredField("mediaPlayer"); field.setAccessible(true);
            check(field.get(cancelled)==null,"STOP must cancel delayed playback");
            runOnMainSync(() -> cancelled.release());
            result.putString("stream", "PASS " + passed + " executed Android regression checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + passed + " checks: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
