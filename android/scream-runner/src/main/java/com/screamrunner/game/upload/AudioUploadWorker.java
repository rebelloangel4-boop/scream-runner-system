package com.screamrunner.game.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AudioUploadWorker v3.0:
 * Handles authenticated device enrollment (X-Device-Token), user-consented session registration,
 * interruption logging, persistent offline upload queueing, and HTTPS upload with SHA-256 deduplication.
 */
public class AudioUploadWorker {
    private static final String TAG = "AudioUploadWorker";
    private static final String PREFS_NAME = "scream_runner_prefs_v3";
    private static final String KEY_BASE_URL = "backend_base_url";
    private static final String KEY_DEVICE_ID = "registered_device_id";
    private static final String KEY_DEVICE_TOKEN = "registered_device_token";
    private static final String KEY_PENDING_QUEUE = "pending_upload_queue_json";
    public static final String DEFAULT_CLOUD_URL = "https://scream-runner-synthetic-test.onrender.com";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface UploadStatusListener {
        void onSessionRegistered(String sessionId, String deviceName);
        void onSegmentUploaded(int seqIndex, String segmentId, double peakDb);
        void onUploadError(String error);
    }

    private static volatile UploadStatusListener statusListener;

    public static void setStatusListener(UploadStatusListener listener) {
        statusListener = listener;
    }

    public static String getBaseUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_BASE_URL, DEFAULT_CLOUD_URL);
    }

    public static void setBaseUrl(Context context, String url) {
        String cleaned = url.trim();
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!cleaned.startsWith("https://") || cleaned.contains("@") || cleaned.contains("?")) {
            throw new IllegalArgumentException("Enter an HTTPS server URL without credentials or query parameters");
        }
        if (!cleaned.equals(getBaseUrl(context))) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(KEY_DEVICE_TOKEN).remove("pairing_code").commit();
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BASE_URL, cleaned)
                .apply();
    }

    public static String getDeviceName() {
        return "Android " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")";
    }

    public static String getOrInitDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String cleanModel = Build.MODEL.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        String generated = "dev_" + UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    public static String getDeviceToken(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_TOKEN, null);
    }

    private static synchronized String ensureDeviceEnrolledSync(Context appContext) {
        String baseUrl = getBaseUrl(appContext);
        String deviceName = getDeviceName();
        String deviceId = getOrInitDeviceId(appContext);
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existingTok = prefs.getString(KEY_DEVICE_TOKEN, null);
        if (existingTok != null && !existingTok.isEmpty()) return existingTok;

        try {
            JSONObject devPayload = new JSONObject();
            devPayload.put("id", deviceId);
            devPayload.put("pairing_code", prefs.getString("pairing_code", ""));
            devPayload.put("name", deviceName);
            devPayload.put("os_version", "Android " + Build.VERSION.RELEASE);
            devPayload.put("app_version", "3.0.0");
            String regResp = postJson(baseUrl + "/api/v1/devices/register", devPayload.toString(), null);
            if (regResp != null) {
                JSONObject resObj = new JSONObject(regResp);
                if (resObj.has("device_id")) {
                    deviceId = resObj.getString("device_id");
                }
                if (resObj.has("device_token")) {
                    existingTok = resObj.getString("device_token");
                }
                prefs.edit()
                        .putString(KEY_DEVICE_ID, deviceId)
                        .putString(KEY_DEVICE_TOKEN, existingTok)
                        .apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "Device enrollment notice: " + e.getMessage());
        }
        return existingTok;
    }

    public static void registerDeviceAndStartSession(Context context, String sessionId) {
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            String baseUrl = getBaseUrl(appContext);
            String deviceName = getDeviceName();
            String deviceToken = ensureDeviceEnrolledSync(appContext);
            String deviceId = getOrInitDeviceId(appContext);

            try {
                JSONObject sessPayload = new JSONObject();
                sessPayload.put("session_id", sessionId);
                sessPayload.put("device_id", deviceId);
                sessPayload.put("device_name", deviceName);
                sessPayload.put("consent_confirmed", true);
                String sessResp = postJson(baseUrl + "/api/v1/sessions/start", sessPayload.toString(), deviceToken);
                if (sessResp != null && statusListener != null) {
                    statusListener.onSessionRegistered(sessionId, deviceName);
                }
                flushPendingQueue(appContext);
            } catch (Exception e) {
                Log.w(TAG, "Session registration notice: " + e.getMessage());
            }
        });
    }

    public static void reportInterruption(Context context, String sessionId, String reason, int durationMs) {
        if (sessionId == null || context == null) return;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                String baseUrl = getBaseUrl(appContext);
                String deviceToken = getDeviceToken(appContext);
                if (deviceToken == null) {
                    deviceToken = ensureDeviceEnrolledSync(appContext);
                }
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("event_type", "INTERRUPTION");
                payload.put("reason", reason);
                payload.put("duration_ms", durationMs);
                postJson(baseUrl + "/api/v1/sessions/interruption", payload.toString(), deviceToken);
            } catch (Exception ignored) {}
        });
    }

    public static void pingDevice(Context context, String statusVal) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                String baseUrl = getBaseUrl(appContext);
                String deviceId = getOrInitDeviceId(appContext);
                String deviceToken = getDeviceToken(appContext);
                if (deviceToken == null) {
                    deviceToken = ensureDeviceEnrolledSync(appContext);
                }
                postJson(baseUrl + "/api/v1/devices/" + deviceId + "/ping?status_val=" + statusVal, "{}", deviceToken);
            } catch (Exception ignored) {}
        });
    }

    public static void stopSession(Context context, String sessionId) {
        if (sessionId == null || context == null) return;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                String baseUrl = getBaseUrl(appContext);
                String deviceToken = getDeviceToken(appContext);
                if (deviceToken == null) {
                    deviceToken = ensureDeviceEnrolledSync(appContext);
                }
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                postJson(baseUrl + "/api/v1/sessions/stop", payload.toString(), deviceToken);
                pingDevice(appContext, "online");
            } catch (Exception e) {
                Log.w(TAG, "Session stop notice: " + e.getMessage());
            }
        });
    }

    private static synchronized void saveToPendingQueue(Context context, JSONObject item) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(KEY_PENDING_QUEUE, "[]"));
            String hash = item.optString("sha256Hash", "");
            for (int i = 0; i < arr.length(); i++) {
                if (item.optString("filePath").equals(arr.getJSONObject(i).optString("filePath"))) {
                    return; // Deduplicate in local queue
                }
            }
            arr.put(item);
            prefs.edit().putString(KEY_PENDING_QUEUE, arr.toString()).commit();
        } catch (Exception ignored) {}
    }

    private static synchronized void removePending(Context context, String filePath) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray queue = new JSONArray(prefs.getString(KEY_PENDING_QUEUE, "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject job = queue.getJSONObject(i);
                if (!filePath.equals(job.optString("filePath"))) kept.put(job);
            }
            prefs.edit().putString(KEY_PENDING_QUEUE, kept.toString()).commit();
        } catch (Exception e) { Log.e(TAG, "Could not update upload queue", e); }
    }

    public static int getPendingQueueSize(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(KEY_PENDING_QUEUE, "[]"));
            return arr.length();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void flushPendingQueue(Context context) {
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray(prefs.getString(KEY_PENDING_QUEUE, "[]"));
                if (arr.length() == 0) return;

                String deviceToken = getDeviceToken(appContext);
                if (deviceToken == null) {
                    deviceToken = ensureDeviceEnrolledSync(appContext);
                }

                JSONArray remaining = new JSONArray();
                String uploadUrl = getBaseUrl(appContext) + "/api/v1/audio/upload";
                String deviceId = getOrInitDeviceId(appContext);
                String deviceName = getDeviceName();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject job = arr.getJSONObject(i);
                    String filePath = job.optString("filePath", "");
                    if (!isShareAuthorized(appContext, filePath)) { removePending(appContext, filePath); continue; }
                    try {
                        String segId = executeMultipartUpload(
                                uploadUrl,
                                deviceToken,
                                job.optString("sessionId", "sess_recovered"),
                                deviceId,
                                deviceName,
                                job.optInt("seqIndex", 1),
                                filePath,
                                job.optString("sha256Hash", ""),
                                job.optLong("startTimeMs", System.currentTimeMillis()),
                                job.optLong("endTimeMs", System.currentTimeMillis()),
                                job.optInt("durationMs", 1000),
                                job.optDouble("peakDb", -12.0),
                                job.optDouble("avgDb", -20.0),
                                job.optString("waveformJson", "[]")
                        );
                        if (segId == null) {
                            remaining.put(job);
                        } else {
                            removePending(appContext, filePath);
                            if (statusListener != null) statusListener.onSegmentUploaded(job.optInt("seqIndex", 1), segId, job.optDouble("peakDb", -12.0));
                        }
                    } catch (Exception e) {
                        if ("HTTP 401".equals(e.getMessage())) {
                            prefs.edit().remove(KEY_DEVICE_TOKEN).commit();
                            deviceToken = ensureDeviceEnrolledSync(appContext);
                            deviceId = getOrInitDeviceId(appContext);
                        }
                        remaining.put(job);
                    }
                }
                // Remove only completed jobs individually; preserve new approvals queued concurrently.
            } catch (Exception ignored) {}
        });
    }

    private static String postJson(String urlStr, String jsonBody, String deviceToken) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (deviceToken != null && !deviceToken.isEmpty()) {
                conn.setRequestProperty("X-Device-Token", deviceToken);
            }
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static void saveLocalRecording(Context context, String sessionId, int seqIndex,
            String filePath, String sha256Hash, long startTimeMs, long endTimeMs,
            int durationMs, double peakDb, double avgDb, String waveformJson) {
        try {
            JSONObject data = new JSONObject();
            data.put("sessionId", sessionId); data.put("seqIndex", seqIndex);
            data.put("sha256Hash", sha256Hash); data.put("startTimeMs", startTimeMs);
            data.put("endTimeMs", endTimeMs); data.put("durationMs", durationMs);
            data.put("peakDb", peakDb); data.put("avgDb", avgDb);
            data.put("waveformJson", waveformJson);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(filePath + ".json")) {
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.e(TAG, "Could not save review metadata", e); }
    }

    private static String fileHash(String filePath) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b));
        return hex.toString();
    }

    private static boolean isShareAuthorized(Context context, String path) {
        try {
            String expected = getBaseUrl(context) + "|" + fileHash(path);
            return expected.equals(context.getSharedPreferences("reviewed_shares_v1", Context.MODE_PRIVATE)
                    .getString(path, ""));
        } catch (Exception e) { return false; }
    }

    public static void revokeShare(Context context, String path) {
        context.getSharedPreferences("reviewed_shares_v1", Context.MODE_PRIVATE).edit().remove(path).commit();
    }

    public static void setPairingCode(Context context, String code) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("pairing_code", code.trim()).commit();
    }

    // Called only by the explicit confirmation button after local audio review.
    public static void shareReviewedRecording(Context context, File file) throws Exception {
        JSONObject m;
        try (FileInputStream in = new FileInputStream(file.getAbsolutePath() + ".json")) {
            m = new JSONObject(readStream(in));
        }
        String actualHash = fileHash(file.getAbsolutePath());
        if (!actualHash.equals(m.getString("sha256Hash"))) throw new Exception("Recording integrity check failed");
        boolean saved = context.getSharedPreferences("reviewed_shares_v1", Context.MODE_PRIVATE).edit()
                .putString(file.getAbsolutePath(), getBaseUrl(context) + "|" + actualHash).commit();
        if (!saved) throw new Exception("Could not save sharing authorization");
        enqueueUpload(context, m.getString("sessionId"), m.getInt("seqIndex"), file.getAbsolutePath(),
                actualHash, m.getLong("startTimeMs"), m.getLong("endTimeMs"), m.getInt("durationMs"),
                m.getDouble("peakDb"), m.getDouble("avgDb"), m.getString("waveformJson"));
    }

    public static void enqueueUpload(
            Context context,
            String sessionId,
            int seqIndex,
            String filePath,
            String sha256Hash,
            long startTimeMs,
            long endTimeMs,
            int durationMs,
            double peakDb,
            double avgDb,
            String waveformJson
    ) {
        final Context appContext = context.getApplicationContext();
        if (!isShareAuthorized(appContext, filePath)) return;
        try {
            JSONObject job = new JSONObject();
            job.put("sessionId", sessionId); job.put("seqIndex", seqIndex);
            job.put("filePath", filePath); job.put("sha256Hash", sha256Hash);
            job.put("startTimeMs", startTimeMs); job.put("endTimeMs", endTimeMs);
            job.put("durationMs", durationMs); job.put("peakDb", peakDb);
            job.put("avgDb", avgDb); job.put("waveformJson", waveformJson);
            saveToPendingQueue(appContext, job);
        } catch (Exception e) { return; }
        executor.execute(() -> {
            if (!isShareAuthorized(appContext, filePath)) return;
            boolean success = false;
            int attempt = 0;
            int maxAttempts = 3;
            long backoffMs = 1500;
            String deviceToken = getDeviceToken(appContext);
            if (deviceToken == null) {
                deviceToken = ensureDeviceEnrolledSync(appContext);
            }
            String deviceId = getOrInitDeviceId(appContext);
            String deviceName = getDeviceName();

            while (!success && attempt < maxAttempts) {
                if (!isShareAuthorized(appContext, filePath)) return;
                attempt++;
                try {
                    String uploadUrl = getBaseUrl(appContext) + "/api/v1/audio/upload";
                    String segId = executeMultipartUpload(
                            uploadUrl,
                            deviceToken,
                            sessionId,
                            deviceId,
                            deviceName,
                            seqIndex,
                            filePath,
                            sha256Hash,
                            startTimeMs,
                            endTimeMs,
                            durationMs,
                            peakDb,
                            avgDb,
                            waveformJson
                    );
                    if (segId != null) {
                        success = true;
                        removePending(appContext, filePath);
                        if (statusListener != null) {
                            statusListener.onSegmentUploaded(seqIndex, segId, peakDb);
                        }
                    }
                } catch (Exception e) {
                    if ("HTTP 401".equals(e.getMessage())) {
                        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_DEVICE_TOKEN).commit();
                        deviceToken = ensureDeviceEnrolledSync(appContext);
                        deviceId = getOrInitDeviceId(appContext);
                    }
                    if (attempt == maxAttempts) {
                        try {
                            JSONObject job = new JSONObject();
                            job.put("sessionId", sessionId);
                            job.put("seqIndex", seqIndex);
                            job.put("filePath", filePath);
                            job.put("sha256Hash", sha256Hash);
                            job.put("startTimeMs", startTimeMs);
                            job.put("endTimeMs", endTimeMs);
                            job.put("durationMs", durationMs);
                            job.put("peakDb", peakDb);
                            job.put("avgDb", avgDb);
                            job.put("waveformJson", waveformJson);
                            saveToPendingQueue(appContext, job);
                            reportInterruption(appContext, sessionId, "Network upload queued offline", durationMs);
                        } catch (Exception ignored) {}
                        if (statusListener != null) {
                            statusListener.onUploadError("Queued offline (will auto-retry)");
                        }
                    }
                }

                if (!success && attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
    }

    private static String executeMultipartUpload(
            String uploadUrlStr,
            String deviceToken,
            String sessionId,
            String deviceId,
            String deviceName,
            int seqIndex,
            String filePath,
            String sha256Hash,
            long startTimeMs,
            long endTimeMs,
            int durationMs,
            double peakDb,
            double avgDb,
            String waveformJson
    ) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        String boundary = "----ScreamRunnerBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        String mimeType = file.getName().endsWith(".m4a") ? "audio/mp4" : "audio/wav";

        HttpURLConnection conn = null;
        try {
            URL url = new URL(uploadUrlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (deviceToken != null && !deviceToken.isEmpty()) {
                conn.setRequestProperty("X-Device-Token", deviceToken);
            }

            try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                writeFormField(dos, boundary, "session_id", sessionId);
                writeFormField(dos, boundary, "device_id", deviceId);
                writeFormField(dos, boundary, "device_name", deviceName);
                writeFormField(dos, boundary, "seq_index", String.valueOf(seqIndex));
                writeFormField(dos, boundary, "start_time", String.valueOf(startTimeMs / 1000.0));
                writeFormField(dos, boundary, "end_time", String.valueOf(endTimeMs / 1000.0));
                writeFormField(dos, boundary, "duration_ms", String.valueOf(durationMs));
                writeFormField(dos, boundary, "peak_db", String.valueOf(peakDb));
                writeFormField(dos, boundary, "avg_db", String.valueOf(avgDb));
                writeFormField(dos, boundary, "sharing_authorized", "true");
                writeFormField(dos, boundary, "recording_kind", file.getName().startsWith("full_") ? "full" : "speech");
                writeFormField(dos, boundary, "speech_confidence", "0"); // No calibrated speech probability available
                writeFormField(dos, boundary, "file_hash", sha256Hash);
                if (waveformJson != null) {
                    writeFormField(dos, boundary, "waveform_json", waveformJson);
                }

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"audio_file\"; filename=\"" + file.getName() + "\"" + lineEnd);
                dos.writeBytes("Content-Type: " + mimeType + lineEnd + lineEnd);

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                }
                dos.writeBytes(lineEnd);
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                dos.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String respStr = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(respStr);
                if (json.has("segment_id")) {
                    return json.getString("segment_id");
                }
                return "ok";
            } else {
                throw new Exception("HTTP " + code);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void writeFormField(DataOutputStream dos, String boundary, String name, String value) throws Exception {
        String lineEnd = "\r\n";
        dos.writeBytes("--" + boundary + lineEnd);
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"" + lineEnd + lineEnd);
        dos.write((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
        dos.writeBytes(lineEnd);
    }
}
