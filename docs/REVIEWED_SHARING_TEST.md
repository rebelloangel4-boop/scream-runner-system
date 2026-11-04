# Reviewed sharing patch — existing v3 architecture

## Change audit
Before development, this Work session had created 15 local copies only. Fourteen
had one extra final newline; gradlew.bat also had CRLF normalized to LF. No source
logic was changed, no files deleted, no remote commits or pushes made. All 45
selected source/build files were subsequently restored from upstream a5d1c87 and
verified using Git blob SHA-1. The local snapshot intentionally excludes tracked
server recordings/database/backups. A separate branch/PR contains subsequent fixes.

The earlier security PR #1 targets an obsolete backend. It was inspected but not
merged: current main already uses hashed administrator passwords and typed tokens.

## Private backend configuration
Use an existing HTTPS-capable host with persistent private disk. Do not use the
tracked backend/storage database or recordings as production state. Set these
through the host's private environment-variable UI, never in Git or chat:

- SCREAM_STORAGE_DIR: absolute persistent private directory, outside the public repository.
- JWT_SECRET: at least 32 random characters, retained across redeploys.
- ADMIN_SETUP_TOKEN: at least 32 random characters, used only for first account setup.
- DEVICE_PAIRING_CODE: at least 16 random characters.

Generate independent secrets locally with `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
Install `backend/requirements.txt`, then run
`uvicorn backend.main:app --host 0.0.0.0 --port 8000 --workers 1` behind HTTPS.
Use `/api/v1/health` for health checks. Keep database and audio permissions private.

On Sam's phone, configure the HTTPS URL, then enter the private setup token,
username and a unique strong password. Save the returned recovery key privately.
Remove ADMIN_SETUP_TOKEN after setup. Use PAIR DEVICE to display the private pairing
code; enter it with the same HTTPS URL in Scream Runner settings. Pairing permits
only authenticated uploads; recording review and per-file sharing remain required.

A synthetic-only temporary free deployment is documented in TEMPORARY_TEST_DEPLOYMENT.md.
Ephemeral storage is unsuitable for retaining private recordings. Use that deployment
only with its provided synthetic fixture, never with microphone recordings.

## Phone acceptance checks (not yet verified on hardware)
1. Start recording explicitly. Verify the microphone indicator and STOP notification.
2. Speak, pause, stop. Check LOCAL CLIPS. No recording should appear on Sam's dashboard yet.
3. Preview one clip, then choose SHARE THIS RECORDING and AUTHORIZE UPLOAD.
4. Log into Sam's dashboard. Verify its waveform, measured dBFS peak, playback, scrubbing,
   speed control, and download notification. Verify unshared clips remain absent.
5. Repeat sharing while offline. Reconnect and use RETRY SHARED QUEUE or reopen the app.
   Exactly one server segment should exist after retries.
6. Delete an unshared clip. Confirm it cannot be uploaded. Local deletion does not
   retract an already delivered copy from Sam; delete that copy separately in the dashboard.
7. Test STOP, permission denial/revocation, incoming calls, screen off, app process death,
   Android 14/15, and device battery restrictions. Never restart recording automatically.

## Known limits
These APKs are debug test builds. Automated backend tests do not establish real-device
microphone/codec behavior or six-hour reliability. The recorder now retains all received PCM in 30-second full_* segments as well as
existing speech_* highlight clips, and stops at six hours. These are separate
reviewable files, not a merged six-hour master. Device interruptions can create gaps;
force-stop can lose the current incomplete segment. Speech detection is
heuristic; the app no longer sends a fabricated probability. This patch preserves the
existing game, encoder, VAD, player, waveform, filters, and retry components.

The legacy web client does not have the new review-and-share flow and cannot upload
without the new sharing_authorized field. Use the Android clients for this flow.
