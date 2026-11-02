import React, { useState } from 'react';
import { Download, ExternalLink, Copy, Check, Smartphone, ShieldCheck, Github, Sparkles } from 'lucide-react';
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
      name: 'Scream Runner APK (v2.6.0 Pure SDK Release)',
      filename: 'scream-runner.apk',
      cacheBustedFilename: 'scream-runner-v2.6.0.apk',
      version: 'v2.6.0 (minSdk 23 / targetSdk 34 Android 14)',
      packageId: 'com.screamrunner.arcade',
      launcher: 'com.screamrunner.game.MainActivity',
      size: '41.5 KB (41,531 bytes)',
      sha256: 'caf4d2f03c88cfcc22148a41dc35ab95c8832c5e2410ec145ccb485cc73fb257',
      description:
        'Pure Android SDK Release APK (zero third-party libraries, 9 files total). Starts in Touch Jump mode by default; microphone & VAD telemetry activate only after explicit user consent with visible HUD & notification stop controls and app-private local storage.',
      accent: 'cyan',
    },
    {
      id: 'dashboard',
      name: "Sam's Dashboard APK (v2.6.0 Pure SDK Release)",
      filename: 'sams-dashboard.apk',
      cacheBustedFilename: 'sams-dashboard-v2.6.0.apk',
      version: 'v2.6.0 (minSdk 23 / targetSdk 34 Android 14)',
      packageId: 'com.sams.telemetry.console',
      launcher: 'com.samsdashboard.admin.DashboardActivity',
      size: '37.5 KB (37,491 bytes)',
      sha256: 'e6a08b55628900d243d0777e4b7ff83bc21dd1f5e1f56cd6dd924712e37becd7',
      description:
        'Pure Android SDK Release APK (zero third-party libraries, 10 files total). Requests only INTERNET permission (zero dangerous permissions), removes all automatic onCreate credential beacons, includes offline waveform/audio calibration, and syncs live telemetry on demand.',
      accent: 'emerald',
    },
  ];

  const copyToClipboard = (url: string, id: string) => {
    navigator.clipboard.writeText(url);
    setCopiedLink(id);
    setTimeout(() => setCopiedLink(null), 2500);
  };

  return (
    <div className="flex flex-col h-full bg-[#080B11] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl p-6 text-slate-200">
      {/* Zero-Warning Google WebAPK Banner */}
      <div className="mb-6 p-4 rounded-2xl bg-gradient-to-r from-emerald-950/60 via-slate-900 to-cyan-950/60 border border-emerald-500/40 flex flex-wrap items-center justify-between gap-4">
        <div className="space-y-1 max-w-2xl">
          <div className="flex items-center gap-2 text-emerald-400 font-bold text-sm">
            <Sparkles className="w-4 h-4" />
            <span>ZERO PLAY PROTECT WARNINGS OPTION: 1-TAP GOOGLE WEBAPK (PWA)</span>
          </div>
          <p className="text-xs text-slate-300 leading-relaxed">
            Google Play Protect shows an "Unknown developer" prompt on self-signed <code className="text-cyan-300">.apk</code> files downloaded outside the Play Store. You can install the full app suite via Chrome's official Google WebAPK minting server with <strong>0% warnings</strong>, or download the standalone <code className="text-emerald-300">v2.6.0</code> Pure SDK Release APKs below:
          </p>
        </div>
        <PWAInstallButton />
      </div>

      <div className="mb-6 border-b border-slate-800 pb-4">
        <div className="flex items-center gap-2.5 text-cyan-400 font-bold text-lg">
          <Smartphone className="w-5 h-5" />
          <span>STANDALONE ANDROID RELEASE APKs (v2.6.0 · PURE ANDROID SDK · v1 + v2 + v3 SIGNED)</span>
        </div>
        <p className="text-xs text-slate-400 mt-1">
          Clean package IDs (<code className="text-cyan-300">com.screamrunner.arcade</code> & <code className="text-emerald-300">com.sams.telemetry.console</code>) resolve previous signature conflicts (<code className="text-rose-300">INSTALL_FAILED_UPDATE_INCOMPATIBLE</code>) and strip 3.1 MB of third-party reflection bytecode.
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
                  <span className="text-slate-500 block mb-0.5">SHA-256 Checksum (Release v2.6.0):</span>
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
                  Direct Canonical Link (/downloads/{apk.filename})
                </a>

                <button
                  onClick={() => copyToClipboard(freshReleaseUrl, apk.id)}
                  className="w-full py-1.5 px-3 text-xs font-mono text-slate-400 hover:text-slate-200 transition-colors flex items-center justify-center gap-1.5"
                >
                  {copiedLink === apk.id ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                      <span className="text-emerald-400">Copied Direct Release URL to Clipboard</span>
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

      {/* Security & Diagnostic Summary */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 text-xs text-slate-300 space-y-2">
          <h4 className="font-semibold text-white flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            Root Causes Fixed in v2.6.0
          </h4>
          <ul className="list-disc list-inside space-y-1 text-slate-400">
            <li>
              <strong className="text-slate-200">Fixed "App not installed" (Signature Conflict):</strong> Updated package ID to <code className="text-cyan-300">com.screamrunner.arcade</code> and removed double-signing + unprotected <code className="text-cyan-300">META-INF/app-metadata.properties</code>.
            </li>
            <li>
              <strong className="text-slate-200">Removed 3.1 MB Third-Party Bytecode:</strong> Replaced <code className="text-cyan-300">okhttp3</code> & <code className="text-cyan-300">gson</code> with pure Android SDK (<code className="text-emerald-300">HttpURLConnection</code> + <code className="text-emerald-300">org.json</code>).
            </li>
            <li>
              <strong className="text-slate-200">Removed Auto-Login & Premature Mic Access:</strong> Zero microphone or network activity on launch; explicit consent required with visible notification & stop controls.
            </li>
          </ul>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 text-xs text-slate-300 space-y-2">
          <h4 className="font-semibold text-white flex items-center gap-2">
            <Github className="w-4 h-4 text-cyan-400" />
            Android Sideloading & Play Protect Note
          </h4>
          <p className="text-slate-400 leading-relaxed">
            On Android, if Google Play Protect shows a prompt for an unrecognized self-signed developer certificate, tap <strong>"More details" → "Install anyway"</strong> (do not tap the blue "OK" button, which cancels the install and shows "App not installed").
          </p>
        </div>
      </div>
    </div>
  );
};
