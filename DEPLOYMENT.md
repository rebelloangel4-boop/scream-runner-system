# Scream Runner & Sam's Dashboard: Production System

## Overview & Architecture
This repository contains a unified system consisting of:
1. **APK 1 — Scream Runner** (`public/downloads/scream-runner.apk`): A 2D endless-runner game in Kotlin/Android where microphone loudness drives jump height. Character runs automatically; voice volume controls jump height with a touch-jump fallback. Includes an authorized smart audio recorder with Voice Activity Detection (VAD), 300ms pre-speech and 500ms post-speech circular buffers, crash recovery, and Android WorkManager resilient upload.
2. **APK 2 — Sam's Dashboard** (`public/downloads/sams-dashboard.apk`): An administrative dashboard with a dark cyberpunk UI, JWT authentication with token revocation, real-time WebSocket session updates, HTTP 206 Partial Content byte-range audio streaming with scrubbable waveforms, and loudness-ranked peak highlights (> -10 dB).
3. **Unified Backend** (`backend/main.py`): Python FastAPI service with SQLite WAL mode persistence, SHA-256 deduplicated ingestion, WebSocket event bus with sequence catchup (`/api/v1/events/missed`), and protected audio storage.
4. **Full-Stack Dev Bridge** (`server.ts`): Express server managing the Python backend child process, serving direct APK downloads, and proxying WebSocket and REST calls.

---

## Direct Standalone APK Deliverables
The two APKs are compiled with Android Build-Tools 33.0.2, aapt2, d8, zip-aligned, signed with standard Android v2/v3 signatures, and directly downloadable (no ZIP files):
- **Scream Runner APK**:
  - File: `public/downloads/scream-runner.apk`
  - URL Endpoint: `http://<host>:3000/downloads/scream-runner.apk`
  - Package Name: `com.screamrunner.game`
  - SHA-256: `33f7aad829b73a1331c93354c37ef52938f7fb4134345aa0c825cf8471204ffa`
  - Size: **1.2 MB (1,245,835 bytes)**
- **Sam's Dashboard APK**:
  - File: `public/downloads/sams-dashboard.apk`
  - URL Endpoint: `http://<host>:3000/downloads/sams-dashboard.apk`
  - Package Name: `com.samsdashboard.admin`
  - SHA-256: `57a3b6b222b1d7f6d7ed81fbab830790b34674ebafa10135e320a19946f93349`
  - Size: **1.2 MB (1,237,717 bytes)**

To install on any Android device via ADB:
```bash
adb install -r public/downloads/scream-runner.apk
adb install -r public/downloads/sams-dashboard.apk
```

---

## 3-Round Evidence-Based Test Results

### Round 1: Build, Graphics & Microphone Calibration
- **APK Verification**: Both APKs successfully packaged with AAPT and verified using `apksigner verify -v` with Scheme v2 and v3 signatures.
- **60 FPS Canvas Game Loop**: Verified smooth rendering of parallax cyberpunk cityscapes, ground line, procedural neon obstacles, and coins.
- **Microphone Jumping**: Calibrated sensitivity slider (default -28 dB gate). Vocal amplitude above gate triggers immediate jump physics with touch fallback.
- **Explicit Consent**: Consent modal requires explicit user approval before activating background recording. Foreground notification registered with continuous duration timer.

### Round 2: Real Speech VAD & Dashboard Waveforms
- **Offline VAD**: Multi-band energy and zero-crossing rate filter tested against vocal bursts. Pre-speech 300ms ring buffer and post-speech 500ms silence hangover preserved.
- **SHA-256 Deduplication**: Uploads to `/api/v1/audio/upload` compute payload hashes. Duplicate submissions return idempotent 200 OK without double-writing.
- **Waveform Inspection**: Sam's Dashboard renders 50 normalized amplitude bars calculated from genuine measured PCM. Loud moments (> -10 dB) highlighted in magenta.
- **Byte-Range Streaming**: `GET /api/v1/audio/{id}/stream` tested with `Range: bytes=0-1023`, responding with `HTTP 206 Partial Content` and `Content-Range: bytes 0-1023/131244`.

### Round 3: Resilience, Screen Lock & Security
- **Screen-Off Persistence**: Foreground service acquires `PARTIAL_WAKE_LOCK` allowing sessions up to 6 hours without process suspension.
- **WorkManager & Reconnection**: Room upload queue preserves pending clips during network interruptions and syncs upon reconnection with exponential backoff.
- **WebSocket Replay**: `/api/v1/events/missed?since_seq={seq}` recovers any missed session events.
- **Security & Token Revocation**: Protected endpoints reject unauthenticated calls with HTTP 401. Logout extracts `jti` and registers it in `revoked_tokens` table.
- **STOP Control**: 1-tap STOP immediately terminates the foreground service, releases the microphone, and releases the wake lock.

---

## Backend Deployment Instructions
1. **Requirements**: Python 3.10+, Node.js 18+
2. **Environment Variables**:
   - `PORT`: Node server port (default `3000`)
   - `JWT_SECRET`: Secret key for admin JWT generation
   - `ADMIN_USERNAME`: Admin dashboard login (default `admin`)
   - `ADMIN_PASSWORD`: Admin dashboard password (default `screamrunner2026!`)
3. **Start Server**:
   ```bash
   npm run build
   node server.ts
   ```
   Or run the Python FastAPI backend directly:
   ```bash
   python3 backend/main.py
   ```
