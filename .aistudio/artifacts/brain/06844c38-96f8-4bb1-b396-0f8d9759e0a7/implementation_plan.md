# Scream Runner & Sam's Dashboard: Production-Grade Dual-APK System

An enterprise-grade audio-controlled mobile gaming and authorized speech intelligence platform. Scream Runner features a 60 FPS cyberpunk endless-runner driven by live microphone loudness alongside an authorized, consented background Voice Activity Detection (VAD) audio service with crash recovery and encrypted local storage. Sam's Dashboard is an administrative monitoring application providing secure JWT authentication with token revocation, real-time WebSocket synchronization with missed-event replay, byte-range audio streaming with waveforms, loudness hotspot ranking, and session storage management. Both connect to a hardened Python FastAPI backend with SQLite WAL persistence.

### User Review & Critical Decisions

> [!IMPORTANT]
> The plan is upgraded with the following specific architectural improvements:
> - **Coordinated Audio Manager**: A thread-safe `CoordinatedAudioManager` multiplexes native `AudioRecord` PCM buffers to both the local-only gameplay jump analyzer and the authorized background recording service, completely eliminating hardware capture conflicts.
> - **Robust Multi-Band Offline VAD**: Upgraded from simple energy/ZCR to a multi-band spectral flux and adaptive noise-floor VAD with 300ms pre-speech and 500ms post-speech circular ring buffers, crash recovery state journal, and encrypted local storage (AES-256-GCM / SQLCipher).
> - **Resilient Upload Engine**: Native Android `WorkManager` background workers with network constraints, Room database persistent upload queues, SHA-256 content deduplication, and exponential backoff retry scheduling.
> - **Hardened FastAPI Backend**: Device authentication, JWT access + refresh tokens with revocation blacklist, slowapi rate limiting, durable SQLite database with Write-Ahead Logging (WAL), and HTTP 206 Partial Content byte-range streaming for seamless audio scrubbing.
> - **Sam's Dashboard Intelligence**: Distinguishes measured peak volume from semantic importance; implements WebSocket auto-reconnect with sequence-based missed-event replay; variable playback speed (0.75x–2x); direct audio file exports; and interactive dual-APK live web console.

- **Confirmed Decision 1**: Dual standalone installable APKs (`scream-runner.apk` and `sams-dashboard.apk`) provided as direct download files (never ZIPs), with complete Gradle/Kotlin project source trees and a side-by-side interactive browser preview for immediate sandbox testing.
- **Confirmed Decision 2**: Strict consent & foreground service model: zero clandestine recording, prominent foreground notification with ongoing recording timer, microphone privacy indicator, and immediate 1-tap STOP control.
- **Confirmed Decision 3**: Zero-fabrication audio pipeline: all waveforms, decibel graphs, and loudness rankings are calculated from genuine measured PCM amplitudes and stored AAC files.

---

### 1. Overview & Core Concept

- **What It Does**: 
  - **Scream Runner (APK 1)**: Players control an agile cyberpunk runner where vocal volume drives jump height. In parallel, an explicitly consented foreground audio service captures genuine human speech bursts up to 6 hours (even with screen off), compresses them to AAC, buffers them securely, and syncs them to the cloud via Android WorkManager.
  - **Sam's Dashboard (APK 2)**: Authorized administrators monitor connected recording devices, explore sessions chronologically, review speech clips, inspect high-volume moments, scrub through byte-range streamed audio with interactive waveforms, and manage retention policies.
  - **Unified Backend**: A secure, rate-limited FastAPI server managing device provisioning, JWT authentication with revocation, deduplicated chunk storage, and real-time WebSocket session feeds.
- **Target Audience / Persona**: Mobile gamers seeking innovative voice mechanics, and researchers or authorized personnel requiring continuous, battery-efficient, consented audio event logging with strict data provenance.
- **Key Value**: Eliminates audio hardware contention, guarantees zero audio data loss during network dropouts, and provides an end-to-end verified pipeline with ₹0 infrastructure cost.

---

### 2. User Experience & Visual Design

#### Key User Flows

1. **Scream Runner Gameplay & Coordinated Audio**:
   - Player launches Scream Runner. The title screen prompts for microphone permission.
   - Ambient noise calibration measures the baseline room sound to tune the jump trigger threshold and VAD sensitivity slider.
   - Gameplay runs at 60 FPS: vocal shouting/screaming triggers immediate jump impulse; touch tap acts as an instant fallback.
   - **Authorized Recording Activation**: A separate, clearly labeled "Authorized Smart Logger" toggle presents an explicit consent disclosure modal explaining 6-hour duration, foreground notification, and cloud sync.
   - When consented, a foreground notification appears with an ongoing timer, recording status, and a direct "STOP RECORDING" action button.
2. **Offline VAD & Crash-Resilient Uploads**:
   - `CoordinatedAudioManager` receives 16kHz 16-bit mono PCM. Frame data is sent to the local game analyzer (which never writes to disk) and to the VAD processor.
   - Pre-speech buffer holds 300ms of rolling audio. When speech energy crosses the adaptive noise threshold, recording starts including the pre-buffer.
   - Speech continues until 500ms of silence is detected. The segment is compressed into AAC (.m4a), encrypted, hashed with SHA-256, and enqueued into the local Room database.
   - `UploadWorker` (WorkManager) dispatches pending clips to the backend with exponential backoff on network failures. If the app crashes, uncommitted active sessions are journaled and resumed upon restart.
3. **Sam's Dashboard Administrative Flow**:
   - Admin logs in via a modern dark theme interface using secure credentials.
   - Dashboard establishes an authenticated WebSocket connection. If connection drops, it reconnects automatically with exponential backoff and queries `/api/v1/events/missed?since={seq}` to backfill missed state.
   - Admin views device status badges (`Online`, `Recording`, `Idle`), grouped sessions by date/time, and high-volume speech moments.
   - Clicking an audio clip opens the player: real-time waveform visualizer, dB meter graph, playback rate selector (0.75x, 1x, 1.25x, 1.5x, 2x), seek scrubber with HTTP 206 range requests, and standalone `.m4a` download button.
   - Storage metrics display used space and allow purging old sessions or deleting individual clips.

#### Visual Identity & Theme

- **Aesthetic Direction**: Modern Dark & Cyberpunk Slate (`#0B0F19` background with electric cyan `#00F0FF`, energetic amber `#F59E0B`, and crimson `#EF4444` for peak highlights).
- **Color Palette & Distribution**:
  - `60% Neutral Ground`: `#080B11` deep background with `#0E1420` surface panels.
  - `30% Structural Panels`: `#151E2E` cards with subtle hairline `#1E293B` dividers.
  - `10% Functional Accents`: Cyan (`#00F0FF`) for player actions, magenta (`#EC4899`) for voice thresholds, and emerald (`#10B981`) for nominal recording telemetry.
- **Typography & Hierarchy**:
  - Display / Headlines: `Plus Jakarta Sans` for modern structural clarity.
  - Data / Telemetry / Timestamps: `JetBrains Mono` with strict `tabular-nums` formatting for audio timestamps, duration, and decibels.
  - Body Text: `DM Sans` for comfortable density and mobile legibility.
- **Layout & Anti-Slop Discipline**:
  - Clean unboxed metadata with bullet separators (`·`).
  - No decorative pulsing dots on static text; status indicators reflect genuine WebSocket connection and recording state.
  - Dual-device interactive browser workspace enabling immediate verification of both APKs and the backend.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: CoordinatedAudioManager Architecture**
  - *Chosen Approach*: Single native `AudioRecord` instance feeding an internal dispatcher queue that splits raw audio streams to (a) the in-memory game jump evaluator, and (b) the VAD recording pipeline.
  - *Why*: Android throws hardware conflicts or `AudioRecord.STATE_UNINITIALIZED` if multiple components attempt to open the microphone simultaneously.
  - *Alternatives Considered*: Opening separate audio records (fails on most Android devices due to hardware microphone exclusive locks).
- **Decision 2: Native Android WorkManager with Room Persistent Queue**
  - *Chosen Approach*: Persist all VAD-captured audio clips in local encrypted Room database with upload state (`PENDING`, `UPLOADING`, `COMPLETED`, `FAILED`). WorkManager jobs trigger automatically on network connectivity.
  - *Why*: Survives process death, device reboots, and airplane mode without losing a single speech fragment.
  - *Alternatives Considered*: In-memory Retrofit calls (causes clip loss on sudden network loss or app suspension).
- **Decision 3: Authenticated Byte-Range Audio Streaming & Missed-Event Replay**
  - *Chosen Approach*: FastAPI backend implements HTTP 206 `Accept-Ranges: bytes` streaming for audio endpoints and an event sequence counter (`event_seq`) on the WebSocket bus.
  - *Why*: Allows instant seeking/scrubbing through multi-minute audio files without downloading the entire file first, and prevents dashboard desynchronization after network reconnects.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                   UNIFIED SYSTEM ARCHITECTURE                          │
└────────────────────────────────────────────────────────────────────────┘

 [ APK 1: Scream Runner (Kotlin / Android) ]
  ├── CoordinatedAudioManager (Single AudioRecord multiplexer)
  │    ├── Local Game Loop Pipeline (RMS Decibel Jump Height, 60 FPS)
  │    └── Smart Recording Service (Foreground Service, Notification & STOP)
  │         ├── Multi-Band Offline VAD (Spectral flux + Adaptive Noise Floor)
  │         ├── Circular Ring Buffers (300ms pre-speech, 500ms post-speech)
  │         ├── AAC / M4A MediaCodec Encoder & Local Encrypted Storage
  │         └── WorkManager UploadWorker (Room Queue, SHA-256 Deduplication)
  ▼
┌────────────────────────────────────────────────────────────────────────┐
│              FASTAPI SECURE BACKEND (Python 3.10 / Async)              │
│  ├── /api/v1/auth/login        (Admin JWT Access & Refresh Tokens)     │
│  ├── /api/v1/auth/logout       (Token Revocation & Blacklist)          │
│  ├── /api/v1/devices/register  (Device Key Provisioning)               │
│  ├── /api/v1/audio/upload      (Chunk Ingestion, SHA-256 Deduplication)│
│  ├── /api/v1/audio/{id}/stream (HTTP 206 Byte-Range Audio Streaming)   │
│  ├── /api/v1/events/missed     (Sequence-based Missed Event Sync)      │
│  ├── /ws/live-feed             (Authenticated WebSocket Broadcast)     │
│  └── Storage & DB Engine       (SQLite WAL Mode + Protected Audio Dir) │
└────────────────────────────────────────────────────────────────────────┘
  ▲
 [ APK 2: Sam's Dashboard (Kotlin / Android & Web Dashboard) ]
  ├── JWT Auth Store (EncryptedSharedPreferences / Secure Storage)
  ├── WebSocket Client with Auto-Reconnect & Sequence Synchronization
  ├── Session Explorer (Grouped by date/time, gaps, interruptions)
  ├── Waveform & dB Hotspot Inspector (Calculated from measured PCM)
  ├── Audio Player with Seek & Speed (0.75x–2x, HTTP 206 Range requests)
  └── Storage Manager & Exporter (Direct file downloads, clip cleanup)
```

#### Data Model & State

- **Device**: `id` (UUID), `device_name`, `device_key_hash`, `first_seen`, `last_ping`, `status` (`online`, `recording`, `idle`).
- **Session**: `id` (UUID), `device_id`, `started_at`, `ended_at`, `duration_seconds`, `consent_confirmed`, `total_speech_segments`, `status` (`active`, `stopped`, `interrupted`).
- **AudioSegment**: `id` (UUID), `session_id`, `sequence_index`, `start_timestamp`, `end_timestamp`, `duration_ms`, `peak_db`, `average_db`, `speech_confidence`, `file_path`, `file_hash`, `file_size_bytes`.
- **TokenRevocation**: `jti` (UUID), `revoked_at`, `expires_at`.
- **SystemEvent**: `id`, `event_seq`, `session_id`, `event_type` (`started`, `speech_detected`, `speech_saved`, `upload_success`, `upload_retry`, `screen_off`, `stopped`), `timestamp`, `details`.

#### Component & Flow Mapping

| Component | Handlers & Interactive State |
| :--- | :--- |
| **Scream Runner Engine** | 60 FPS Canvas/OpenGL game loop. Procedural neon obstacle spawner, coin collector, microphone decibel jump velocity, touch fallback jump. |
| **Coordinated Audio Manager** | Initializes 16kHz 16-bit mono `AudioRecord`. Broadcasts non-blocking audio buffers to game engine and VAD engine simultaneously. |
| **Foreground VAD Service** | Enforces consent gate. Shows persistent notification with running time and STOP button. Ring buffer pre/post speech management. |
| **WorkManager Sync Worker** | Reads un-uploaded segments from Room DB. Issues HTTP POST with SHA-256 header. Applies exponential backoff on failures. |
| **Dashboard WebSocket Feed** | Listens for real-time `DEVICE_STATUS`, `SESSION_UPDATE`, and `AUDIO_UPLOADED`. On disconnect, polls missed events via sequence ID. |
| **Waveform & Range Player** | Fetches audio via HTTP 206 range requests. Renders real amplitude bars and sound-level graphs. High-volume moments highlighted with dB ranking. |
| **Storage & Management** | Displays disk usage metrics. Supports clip deletion, full session export, and bulk downloads. |

---

### 5. Verification & Testing Protocol (3 Mandatory Rounds)

- **Round 1: Build, Graphics & Coordinated Audio**
  - Verify standalone APK builds for Scream Runner and Sam's Dashboard.
  - Verify 60 FPS smooth animation loop and obstacle generation.
  - Verify microphone loudness response with calibrated noise gate and fallback touch controls without audio device collisions.
  - Verify explicit consent disclosure modal and foreground notification triggers.
- **Round 2: Real Speech VAD, Uploads & Dashboard Waveform**
  - Verify multi-band VAD detects human speech vs ambient silence with 300ms pre and 500ms post buffer inclusion.
  - Verify AAC audio compression and automatic upload to backend with correct timestamps and SHA-256 hash deduplication.
  - Verify Sam's Dashboard receives WebSocket notification, renders real measured audio waveforms, and accurately highlights high-volume moments.
- **Round 3: Resiliency, Disconnection, Screen Lock & Security**
  - Simulate offline network dropouts: segments persist locally in Room DB and upload when reconnected with zero duplicates.
  - Verify screen-off / background recording continues via the foreground service.
  - Verify unauthenticated requests to `/api/v1/sessions` and `/api/v1/audio` are rejected with HTTP 401.
  - Verify token revocation on logout and rate-limiting enforcement.
  - Verify immediate STOP button terminates background recording and updates session status to `stopped`.
