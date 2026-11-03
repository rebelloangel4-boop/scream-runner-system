import React, { useState } from 'react';
import { Download, ExternalLink, Copy, Check, Smartphone, ShieldCheck, KeyRound } from 'lucide-react';
import { PWAInstallButton } from './PWAInstallButton';

export const DownloadsView: React.FC = () => {
  const [copiedLink, setCopiedLink] = useState<string | null>(null);

  const baseUrl =
    typeof window !== 'undefined'
      ? window.location.origin
      : 'https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app';

  const apkList = [
    {
      id: 'runner',
      name: 'Scream Runner APK (v3.0.0 Pure SDK Release)',
      filename: 'scream-runner.apk',
      cacheBustedFilename: 'scream-runner-v3.0.apk',
      version: 'v3.0.0 (versionCode 6 · minSdk 23 / targetSdk 34 Android 14–16)',
      packageId: 'com.screamrunner.arcade',
      launcher: 'com.screamrunner.game.MainActivity',
      size: '49 KB (Pure Android SDK)',
      sha256: '7d81ab82e2241b03425ee8334c81ea6e4efc43aa87a02a5b05977c539c61a693',
      description:
        'Delta-time 60 FPS voice/touch endless runner with real-time FPS & frame-time (ms) HUD, Title/Pause/GameOver screens, 3 obstacle types, synthesized AudioTrack SFX with mute toggle, Android 14–16 foregroundServiceType="microphone" service with 1-tap STOP notification, adaptive noise-floor VAD (300ms pre-roll / 500ms post-roll), hardware AAC/M4A + WAV encoding, SHA-256 verification, and X-Device-Token authenticated uploads.',
      accent: 'cyan',
    },
    {
      id: 'dashboard',
      name: "Sam's Dashboard APK (v3.0.0 Pure SDK Release)",
      filename: 'sams-dashboard.apk',
      cacheBustedFilename: 'sams-dashboard-v3.0.apk',
      version: 'v3.0.0 (versionCode 6 · minSdk 23 / targetSdk 34 Android 14–16)',
      packageId: 'com.sams.telemetry.console',
      launcher: 'com.samsdashboard.admin.DashboardActivity',
      size: '41 KB (Pure Android SDK)',
      sha256: '27ab5447edad6dad68912b46da6cc5651d850c4c09d6e4f086897d919180ef08',
      description:
        'Hardened administrator console with First-Time Admin Setup (Argon2id password hashing), One-Time Emergency Recovery Key password reset, Android Keystore (AES/GCM/NoPadding) encrypted token storage, automatic refresh-token rotation, HTTP 206 byte-range audio scrubbing, 0.75x–2.0x speed controls, and > -10 dB peak filtering.',
      accent: 'emerald',
    },
  ];

  const copyToClipboard = (url: string, id: string) => {
    navigator.clipboard.writeText(url);
    setCopiedLink(id);
    setTimeout(() => setCopiedLink(null), 2500);
  };

  return (
    <div className="flex flex-col h-full bg-[#080B11] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl p-6 text-slate-200 overflow-y-auto">
      {/* Administrator First-Time Setup & Sign-In Guide */}
      <div className="mb-6 p-4 rounded-2xl bg-gradient-to-r from-cyan-950/70 via-slate-900 to-emerald-950/70 border border-cyan-500/40 flex flex-wrap items-center justify-between gap-4">
        <div className="space-y-1.5 max-w-3xl">
          <div className="flex items-center gap-2 text-cyan-400 font-bold text-sm">
            <KeyRound className="w-4 h-4" />
            <span>FIRST-TIME ADMINISTRATOR SETUP &amp; SIGN-IN (v3.0 ZERO DEFAULT PASSWORDS)</span>
          </div>
          <p className="text-xs text-slate-300 leading-relaxed">
            1. For security compliance, all hardcoded default passwords have been removed in <strong>v3.0</strong>.{' '}
            2. On your first visit to <strong>Sam&apos;s Dashboard</strong> (Web or Android APK), tap <strong>SIGN IN / SETUP</strong> to create your administrator username and password (minimum 10 characters, hashed with <strong>Argon2id</strong>).{' '}
            3. Save the generated <strong>One-Time Emergency Recovery Key</strong> (<code className="text-emerald-300 font-mono">SR-XXXX-...</code>) to reset your password anytime without database wipes.
          </p>
        </div>
        <PWAInstallButton />
      </div>

      <div className="mb-6 border-b border-slate-800 pb-4">
        <div className="flex items-center gap-2.5 text-cyan-400 font-bold text-lg">
          <Smartphone className="w-5 h-5" />
          <span>STANDALONE ANDROID RELEASE APKs (v3.0.0 · PURE ANDROID SDK · v1 + v2 + v3 SIGNED)</span>
        </div>
        <p className="text-xs text-slate-400 mt-1">
          Built with official Gradle AGP 8.1.4 (<code className="text-cyan-300">compileSdk 34</code> / <code className="text-emerald-300">targetSdk 34</code>) and signed with persistent release keystores so updates install cleanly over previous builds.
        </p>
      </div>

      {/* APK Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-8">
        {apkList.map((apk) => {
          const freshReleaseUrl = `${baseUrl}/downloads/${apk.cacheBustedFilename}`;
          const canonicalHttpsUrl = `${baseUrl}/downloads/${apk.filename}`;
          return (
            <div
              key={apk.id}
              className={`p-6 rounded-2xl border flex flex-col justify-between transition-all ${
                apk.accent === 'cyan'
                  ? 'bg-[#0B1324] border-cyan-500/40 hover:border-cyan-400 shadow-[0_0_20px_rgba(0,240,255,0.08)]'
                  : 'bg-[#0A1A18] border-emerald-500/40 hover:border-emerald-400 shadow-[0_0_20px_rgba(16,185,129,0.08)]'
              }`}
            >
              <div>
                <div className="flex items-start justify-between gap-3 mb-2">
                  <h3 className="text-base font-bold text-white tracking-tight">{apk.name}</h3>
                  <span
                    className={`px-2 py-0.5 rounded text-[11px] font-mono font-bold ${
                      apk.accent === 'cyan'
                        ? 'bg-cyan-950 text-cyan-300 border border-cyan-800/60'
                        : 'bg-emerald-950 text-emerald-300 border border-emerald-800/60'
                    }`}
                  >
                    {apk.size}
                  </span>
                </div>

                <div className="text-xs text-slate-400 font-mono mb-1">
                  Package: <span className="text-slate-200">{apk.packageId}</span> · {apk.version}
                </div>
                <div className="text-[11px] text-slate-400 font-mono mb-3">
                  Launcher Activity: <span className="text-cyan-300">{apk.launcher}</span>
                </div>

                <p className="text-xs text-slate-300 leading-relaxed mb-4">{apk.description}</p>

                <div className="bg-black/40 rounded-lg p-2.5 border border-slate-800 text-[10px] font-mono text-slate-400 mb-6 break-all">
                  <span className="text-slate-500 block mb-0.5">SHA-256 Checksum (Release v3.0.0):</span>
                  {apk.sha256}
                </div>
              </div>

              {/* Direct Native HTTPS Links */}
              <div className="space-y-2.5 pt-4 border-t border-slate-800/80">
                <a
                  href={freshReleaseUrl}
                  download={apk.cacheBustedFilename}
                  target="_blank"
                  rel="noopener noreferrer"
                  className={`w-full py-2.5 px-4 text-xs font-bold rounded-xl transition-all flex items-center justify-center gap-2 shadow-lg ${
                    apk.accent === 'cyan'
                      ? 'bg-cyan-400 hover:bg-cyan-300 text-[#080B11] shadow-cyan-500/20'
                      : 'bg-emerald-400 hover:bg-emerald-300 text-[#080B11] shadow-emerald-500/20'
                  }`}
                >
                  <Download className="w-4 h-4" />
                  DOWNLOAD {apk.cacheBustedFilename.toUpperCase()}
                </a>

                <a
                  href={canonicalHttpsUrl}
                  download={apk.filename}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="w-full py-2 px-3 text-xs font-medium text-slate-300 hover:text-white bg-slate-900 hover:bg-slate-800 rounded-lg border border-slate-800 transition-colors flex items-center justify-center gap-1.5"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  Canonical Link (/downloads/{apk.filename})
                </a>

                <button
                  onClick={() => copyToClipboard(freshReleaseUrl, apk.id)}
                  className="w-full py-1.5 px-3 text-xs font-mono text-slate-400 hover:text-slate-200 transition-colors flex items-center justify-center gap-1.5"
                >
                  {copiedLink === apk.id ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                      <span className="text-emerald-400">Copied Direct v3.0 URL to Clipboard</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy Direct Release URL</span>
                    </>
                  )}
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {/* Security & Architecture Summary */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 text-xs text-slate-300 space-y-2">
          <h4 className="font-semibold text-white flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            What Was Repaired &amp; Hardened in v3.0.0
          </h4>
          <ul className="list-disc list-inside space-y-1 text-slate-400">
            <li>
              <strong className="text-slate-200">True Android 14–16 Foreground Service:</strong> <code className="text-cyan-300">SmartRecordingService</code> extends <code className="text-cyan-300">android.app.Service</code> with <code className="text-cyan-300">foregroundServiceType=&quot;microphone&quot;</code> and 1-tap notification STOP action.
            </li>
            <li>
              <strong className="text-slate-200">Zero UI-Thread Frame Stutter:</strong> Removed 60Hz <code className="text-cyan-300">runOnUiThread()</code> calls from <code className="text-cyan-300">MainActivity.updatePhysics()</code> and added nanosecond delta-time physics + frame-time (<code className="text-cyan-300">ms</code>) HUD.
            </li>
            <li>
              <strong className="text-slate-200">Argon2id + Android Keystore + Device Tokens:</strong> Replaced default passwords with first-time setup, AES-GCM Android Keystore token storage, and <code className="text-emerald-300">X-Device-Token</code> upload authentication.
            </li>
          </ul>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 text-xs text-slate-300 space-y-2">
          <h4 className="font-semibold text-white flex items-center gap-2">
            <Smartphone className="w-4 h-4 text-cyan-400" />
            Android Installation &amp; Upgrade Notes
          </h4>
          <p className="text-slate-400 leading-relaxed">
            Both v3.0 APKs are signed with the same release keys used in v2.6/v2.7 so you can install them directly as an update without <code className="text-rose-300">INSTALL_FAILED_UPDATE_INCOMPATIBLE</code>. If Android Play Protect displays an unrecognized developer prompt on sideloading, tap <strong>&quot;More details&quot; → &quot;Install anyway&quot;</strong>.
          </p>
        </div>
      </div>
    </div>
  );
};
