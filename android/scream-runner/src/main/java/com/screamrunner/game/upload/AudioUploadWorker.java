package com.screamrunner.game.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

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
 * AudioUploadWorker handles user-consented session registration and HTTPS upload
 * of locally saved WAV clips using pure Android SDK (HttpURLConnection & org.json).
 */
public class AudioUploadWorker {
    private static final String TAG = "AudioUploadWorker";
    private static final String PREFS_NAME = "scream_runner_prefs";
    private static final String KEY_BASE_URL = "backend_base_url";
    private static final String KEY_DEVICE_ID = "registered_device_id";
    public static final String DEFAULT_CLOUD_URL = "https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app";

    private static final ExecutorService executor = Executors.newCachedThreadPool();

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
        String generated = "dev_" + cleanModel;
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    public static void registerDeviceAndStartSession(Context context, String sessionId) {
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            String baseUrl = getBaseUrl(appContext);
            String deviceName = getDeviceName();
            String deviceId = getOrInitDeviceId(appContext);

            try {
                JSONObject devPayload = new JSONObject();
                devPayload.put("id", deviceId);
                devPayload.put("name", deviceName);
                String regResp = postJson(baseUrl + "/api/v1/devices/register", devPayload.toString());
                if (regResp != null) {
                    JSONObject resObj = new JSONObject(regResp);
                    if (resObj.has("device_id")) {
                        deviceId = resObj.getString("device_id");
                        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_DEVICE_ID, deviceId)
                                .apply();
                    }
                }

                JSONObject sessPayload = new JSONObject();
                sessPayload.put("session_id", sessionId);
                sessPayload.put("device_id", deviceId);
                sessPayload.put("device_name", deviceName);
                sessPayload.put("consent_confirmed", true);
                String sessResp = postJson(baseUrl + "/api/v1/sessions/start", sessPayload.toString());
                if (sessResp != null && statusListener != null) {
                    statusListener.onSessionRegistered(sessionId, deviceName);
                }
            } catch (Exception e) {
                Log.w(TAG, "Session registration notice: " + e.getMessage());
            }
        });
    }

    public static void pingDevice(Context context, String statusVal) {
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                String baseUrl = getBaseUrl(appContext);
                String deviceId = getOrInitDeviceId(appContext);
                postJson(baseUrl + "/api/v1/devices/" + deviceId + "/ping?status_val=" + statusVal, "{}");
            } catch (Exception ignored) {}
        });
    }

    public static void stopSession(Context context, String sessionId) {
        if (sessionId == null) return;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                String baseUrl = getBaseUrl(appContext);
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                postJson(baseUrl + "/api/v1/sessions/stop", payload.toString());
                pingDevice(appContext, "online");
            } catch (Exception e) {
                Log.w(TAG, "Session stop notice: " + e.getMessage());
            }
        });
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
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
        executor.execute(() -> {
            boolean success = false;
            int attempt = 0;
            int maxAttempts = 3;
            long backoffMs = 1500;
            String deviceId = getOrInitDeviceId(appContext);
            String deviceName = getDeviceName();

            while (!success && attempt < maxAttempts) {
                attempt++;
                try {
                    String uploadUrl = getBaseUrl(appContext) + "/api/v1/audio/upload";
                    String segId = executeMultipartUpload(
                            uploadUrl,
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
                        if (statusListener != null) {
                            statusListener.onSegmentUploaded(seqIndex, segId, peakDb);
                        }
                    }
                } catch (Exception e) {
                    if (attempt == maxAttempts && statusListener != null) {
                        statusListener.onUploadError("Saved locally (" + e.getMessage() + ")");
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
                writeFormField(dos, boundary, "speech_confidence", "0.96");
                writeFormField(dos, boundary, "file_hash", sha256Hash);
                if (waveformJson != null) {
                    writeFormField(dos, boundary, "waveform_json", waveformJson);
                }

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"audio_file\"; filename=\"" + file.getName() + "\"" + lineEnd);
                dos.writeBytes("Content-Type: audio/wav" + lineEnd + lineEnd);

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
