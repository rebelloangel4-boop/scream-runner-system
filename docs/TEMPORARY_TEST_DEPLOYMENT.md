# Temporary synthetic-only test deployment

Backend base URL: https://scream-runner-synthetic-test.onrender.com
Health: https://scream-runner-synthetic-test.onrender.com/api/v1/health
Render service: https://dashboard.render.com/web/srv-dblok0vf3r2c73e8uta0
Source: PR #5, branch `work/reviewed-sharing-v3`; main remains separate.
Verified application/build commit: `c04d5f5d6cdbfceff16756cf526da962053268c5`.

This is a free temporary test service, not durable production storage. Only the exact
synthetic tone fixture is accepted. Microphone recordings and backup imports are
rejected by this deployment. No private audio was used in testing. The fixture is
explicitly labelled synthetic and contains no speech.

## Private administrator access

1. Open the Render service above using your own Render account.
2. Open Environment. Privately reveal/copy `ADMIN_INITIAL_PASSWORD`.
3. In Sam's Dashboard, use username `admin` and that generated password.
   Both rebuilt APKs default to the backend URL above. If an earlier installation
   has a saved URL, change it in the app's server settings.
4. Keep `ADMIN_RECOVERY_KEY` private for password recovery. Do not paste any secret
   into chat. JWT_SECRET and DEVICE_PAIRING_CODE are also held only in Render's
   environment settings. No password is embedded in either APK or source code.

The admin account is privately bootstrapped from environment configuration. You do
not need an initial-setup token on this deployment. It is intentionally impossible
for an unauthenticated visitor to claim the admin account through the setup API.

## Two-phone synthetic test

1. In Sam's Dashboard, sign in and tap PAIR DEVICE. Privately give the displayed code
   to the participating user.
2. In Scream Runner settings, confirm the backend URL, enter the pairing code, and
   save. Reopen settings and tap ADD SYNTHETIC TEST CLIP (NO MICROPHONE).
3. Open LOCAL CLIPS -> REVIEW / SHARE CLIPS. Choose `full_SYNTHETIC_TEST.wav`, listen
   to its preview, and explicitly confirm sharing this one clip.
4. Refresh Sam's Dashboard. Choose the new `synthetic_phone_...` session; test its
   waveform, loud peak highlight, scrubbing, playback speed, and EXPORT CLIP.
5. To exercise the phone's queue, authorize a synthetic clip while offline, then
   reconnect and tap RETRY SHARED QUEUE or reopen Scream Runner. No unapproved file
   should upload. Retry deduplication is tested at the HTTP layer; Android process
   death and connectivity behavior still require this device check.

These are debug test APKs. If Android reports a signing conflict with an older APK,
do not uninstall an app containing unexported recordings; its local files would be
removed. Installation/update compatibility has not been tested on your phones.

## Evidence

- GitHub Actions run 41899357009: both APK builds and 15 isolated backend tests passed.
- The real HTTP smoke test passed 20 checks in CI.
- The deployed service passed the same 20 checks over external HTTPS: login, pairing,
  review authorization rejection, non-fixture rejection, authorized upload, retry
  deduplication, waveform, measured dBFS highlight, authenticated byte-range streaming,
  download byte integrity, and denial of unauthorized/device-only recording access.
- Controlled same-commit redeploy passed 7 restart checks: instance replacement,
  loss of temporary audio, rejection of old access/refresh/device tokens, successful
  administrator login, and device re-pairing. The public HTTP smoke test also passed
  after redeploy.
- Synthetic fixture SHA-256: `4ce2615e0595651246af9d5ff24bc31df4c048798fa7049c713a36010c8cc34a`.
- Scream Runner APK SHA-256: `f93e128264e2fa8f69512a3ddf47864e5374d68f98d7c358dd572e4140e3996a`.
- Sam's Dashboard APK SHA-256: `b7fea53f4b02f6dc35c86317c7ab922b0c1bdf8051084db4922448b1d1928d47`.

## Remaining physical tests

The Android UI was not operated on a physical device in this run. Actual microphone
quality, AAC hardware encoding, speech detection, six-hour sessions, screen-off
behavior, notification STOP, app termination, and audible playback on your phones
remain unverified. HTTP byte integrity is not a substitute for listening on a phone.

All temporary server audio and device records can disappear on a restart or idle
spin-down. Environment-held admin credentials remain configured. After a reset,
sign in again; Scream Runner can re-pair using its saved pairing code and retry only
previously approved uploads. Keep private recordings off this test service.
