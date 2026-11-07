# Scream Runner / Sam's Dashboard 3.0.3 release candidate

This successor to PR #5 preserves the existing apps and backend. It is not production acceptance. Main and PR #5 are unchanged. Temporary Render deployment remains on PR #5 with synthetic-only upload enforcement.

## Implemented corrections
- Capture owns its AudioRecord instance until its thread exits; start/read errors are surfaced and stop recording.
- Activity microphone listener detaches in background; service recording remains user controlled. UI resynchronizes with notification STOP and service state.
- Game controls scroll on smaller displays; Android 6 local-clip sorting is compatible; destroyed activities detach upload listeners.
- Playback generation cancels delayed downloads after STOP, bounds download size, cleans temporary files and releases the worker. Selected speed applies after prepare.
- Administrator token storage fails closed when Keystore encryption fails; plaintext/base64 fallback is removed. Backup/transfer rules exclude app data.
- Waveform bins include every sample. AAC muxer finalization errors trigger WAV fallback.
- Backend requires expiry claims and rejects old access/refresh tokens after password changes.
- Uploads reject malformed audio containers and use fsync/atomic file publication. Container validation is not a full codec/security scan.
- Backup restore validates identifiers, storage boundaries, size and checksum before mutations and preserves full/speech metadata.
- Deduplication includes recording time/sequence/kind so identical audio at different times survives. SQLite migration copies every existing segment transactionally.
- Retention rejects invalid durations and updates session counts.

## Verified locally
- 23 isolated backend tests PASS.
- 20 real loopback HTTP synthetic workflow checks PASS.
- Both debug app APKs and instrumentation APKs built.
- Android lint rerun after fixing minimum-API and backup-rule failures; final result recorded in delivery report.
- Existing Render HTTPS health responded healthy and synthetic_test_only=true. The new changes are not deployed there.

## Production blockers and limits
1. No durable production volume or private backup destination is configured. Render free service is ephemeral and accepts only its synthetic fixture. Do not disable this guard or send private audio there. No paid resources were enabled.
2. A persistent owner-controlled release keystore is not available. `android/release-signing.gradle` requires private SCREAM_RELEASE_KEYSTORE, SCREAM_RELEASE_STORE_PASSWORD, SCREAM_RELEASE_KEY_ALIAS and SCREAM_RELEASE_KEY_PASSWORD. Missing settings intentionally fail release packaging. Debug APKs are test candidates, regardless of requested filename.
3. Physical phone testing is unavailable. Microphone quality, OEM battery restrictions, six-hour endurance, screen-off capture, interruption behavior and two-phone acceptance are not verified.
4. Full audio is stored as separate 30-second clips plus speech candidates. In-flight PCM/encoder jobs can be lost on process death. Crash-proof chunk journaling is not implemented. Do not promise complete six-hour preservation.
5. Approved upload queue persists, but retries rely on reopening the app/manual Retry and immediate backoff, not an OS-scheduled background job. Partial upload bytes are retransmitted; no byte-offset resume protocol exists.
6. Dashboard plays individual recordings; it does not stitch a whole session into gap-aware continuous playback. Missing-segment display and calibrated speech classification remain incomplete.
7. M4A/ADTS validation checks framing, not successful decode of arbitrary content. Backup restore rejects malformed paths/checksums but filesystem and SQLite are not one atomic transaction if the disk fails.
8. Readiness for 60 FPS cannot be certified from source or a software emulator. Measure frame timing on target phones.

## Installation and private setup
Install only Scream Runner on Leo's phone and Sam's Dashboard on Sam's phone. Open each APK on Android and allow installation for the downloader when prompted. Never uninstall an existing app with unsaved recordings to resolve a signing conflict; obtain a matching signing key or export the recordings first.

For the existing synthetic test service, use https://scream-runner-synthetic-test.onrender.com. On your Render service's Environment page, privately obtain ADMIN_INITIAL_PASSWORD; sign into the dashboard as admin. Never put passwords in chat. Sam can display the pairing code and give only that code to Leo. In Scream Runner Settings, save the URL/code, add the synthetic test clip, then preview and explicitly approve sharing under Local Clips. Test retry, playback, waveform and export. Private microphone audio will be rejected by this test server.

For a future durable deployment, set SCREAM_STORAGE_DIR outside the repository on persistent private storage, use independent JWT_SECRET / ADMIN_SETUP_TOKEN / DEVICE_PAIRING_CODE, serve FastAPI behind HTTPS with one worker, create the admin using the private setup token and remove that setup token after use. Store encrypted off-host backups and verify restoration before accepting private recordings. Keep the SQLite database, .auth_epoch and JWT secret consistent across restarts. Password changes invalidate old tokens in this candidate. Do not reuse the tracked sample database as production state.
