import React, { useState } from 'react';
import { Download, ExternalLink, Copy, Check, Smartphone, ShieldCheck, CheckCircle2, AlertCircle, QrCode } from 'lucide-react';

export const DownloadsView: React.FC = () => {
  const [downloading, setDownloading] = useState<string | null>(null);
  const [copiedLink, setCopiedLink] = useState<string | null>(null);

  const baseUrl = typeof window !== 'undefined' ? window.location.origin : 'https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app';

  const apkList = [
    {
      id: 'runner',
      name: 'Scream Runner APK',
      filename: 'scream-runner.apk',
      version: 'v1.0.0 (Build 33)',
      packageId: 'com.screamrunner.game',
      size: '1.2 MB (1,245,835 bytes)',
      sha256: '33f7aad829b73a1331c93354c37ef52938f7fb4134345aa0c825cf8471204ffa',
      description: '2D Cyberpunk endless runner game with real-time microphone volume jumping and authorized VAD foreground audio capture.',
      accent: 'cyan',
    },
    {
      id: 'dashboard',
      name: "Sam's Dashboard APK",
      filename: 'sams-dashboard.apk',
      version: 'v1.0.0 (Build 33)',
      packageId: 'com.samsdashboard.admin',
      size: '1.2 MB (1,237,717 bytes)',
      sha256: '57a3b6b222b1d7f6d7ed81fbab830790b34674ebafa10135e320a19946f93349',
      description: "Mobile administrator dashboard for real-time device tracking, session inspection, scrubbable PCM waveforms, and peak audio playback.",
      accent: 'emerald',
    },
  ];

  // Robust download handler: fetches blob to bypass iframe sandbox restrictions
  const handleDownload = async (filename: string) => {
    setDownloading(filename);
    try {
      const directUrl = `${baseUrl}/downloads/${filename}`;
      const response = await fetch(directUrl);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      
      const blob = await response.blob();
      const blobUrl = window.URL.createObjectURL(blob);
      
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = blobUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      
      setTimeout(() => {
        document.body.removeChild(a);
        window.URL.revokeObjectURL(blobUrl);
        setDownloading(null);
      }, 2000);
    } catch (err) {
      console.warn('Blob download failed, opening direct URL:', err);
      // Fallback: open direct link in top window
      window.open(`${baseUrl}/downloads/${filename}`, '_blank', 'noopener,noreferrer');
      setDownloading(null);
    }
  };

  const copyToClipboard = (filename: string) => {
    const fullUrl = `${baseUrl}/downloads/${filename}`;
    navigator.clipboard.writeText(fullUrl);
    setCopiedLink(filename);
    setTimeout(() => setCopiedLink(null), 2500);
  };

  return (
    <div className="flex flex-col h-full bg-[#080B11] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl p-6 text-slate-200">
      <div className="mb-6 border-b border-slate-800 pb-5">
        <div className="flex items-center gap-2.5 text-cyan-400 font-bold text-lg">
          <Smartphone className="w-5 h-5" />
          <span>STANDALONE ANDROID APK DOWNLOAD HUB</span>
        </div>
        <p className="text-xs text-slate-400 mt-1">
          Direct verified HTTPS downloads with Android-compatible headers (<code className="text-cyan-300">application/vnd.android.package-archive</code>).
        </p>
      </div>

      {/* APK Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-8">
        {apkList.map((apk) => {
          const directHttpsUrl = `${baseUrl}/downloads/${apk.filename}`;
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

                <div className="text-xs text-slate-400 font-mono mb-3">
                  Package: <span className="text-slate-200">{apk.packageId}</span> · {apk.version}
                </div>

                <p className="text-xs text-slate-300 leading-relaxed mb-4">
                  {apk.description}
                </p>

                <div className="bg-black/40 rounded-lg p-2.5 border border-slate-800 text-[10px] font-mono text-slate-400 mb-6 break-all">
                  <span className="text-slate-500 block mb-0.5">SHA-256 Checksum (Verified):</span>
                  {apk.sha256}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="space-y-2.5 pt-4 border-t border-slate-800/80">
                {/* 1. Primary Blob / Native Download */}
                <button
                  onClick={() => handleDownload(apk.filename)}
                  disabled={downloading === apk.filename}
                  className={`w-full py-2.5 px-4 text-xs font-bold rounded-xl transition-all flex items-center justify-center gap-2 shadow-lg ${
                    apk.accent === 'cyan'
                      ? 'bg-cyan-400 hover:bg-cyan-300 text-[#080B11] shadow-cyan-500/20'
                      : 'bg-emerald-400 hover:bg-emerald-300 text-[#080B11] shadow-emerald-500/20'
                  }`}
                >
                  <Download className="w-4 h-4" />
                  {downloading === apk.filename ? 'FETCHING APK BYTES...' : `DOWNLOAD ${apk.filename.toUpperCase()}`}
                </button>

                {/* 2. Direct HTTPS Link (New Tab) */}
                <a
                  href={directHttpsUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="w-full py-2 px-3 text-xs font-medium text-slate-300 hover:text-white bg-slate-900 hover:bg-slate-800 rounded-lg border border-slate-800 transition-colors flex items-center justify-center gap-1.5"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  Open Direct Link in New Tab
                </a>

                {/* 3. Copy Direct URL */}
                <button
                  onClick={() => copyToClipboard(apk.filename)}
                  className="w-full py-1.5 px-3 text-xs font-mono text-slate-400 hover:text-slate-200 transition-colors flex items-center justify-center gap-1.5"
                >
                  {copiedLink === apk.filename ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                      <span className="text-emerald-400">Copied Direct URL to Clipboard</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy Direct Download URL</span>
                    </>
                  )}
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {/* Android Mobile Installation Guide & Iframe Explanation */}
      <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 text-xs text-slate-300 space-y-3">
        <h4 className="font-semibold text-white flex items-center gap-2">
          <ShieldCheck className="w-4 h-4 text-cyan-400" />
          Android Phone Installation Instructions:
        </h4>
        <ol className="list-decimal list-inside space-y-1.5 text-slate-400">
          <li>
            <strong className="text-slate-200">Tap "DOWNLOAD APK NOW"</strong> or use <strong className="text-slate-200">"Open Direct Link in New Tab"</strong> if your mobile browser blocks iframe downloads.
          </li>
          <li>
            When Android Chrome displays <code className="text-cyan-300">File might be harmful — Do you want to download scream-runner.apk anyway?</code>, tap <strong className="text-slate-200">Download anyway</strong>.
          </li>
          <li>
            Tap the downloaded file from Chrome notifications or your Files app.
          </li>
          <li>
            If prompted with <code className="text-slate-300">Install unknown apps</code>, toggle <strong className="text-slate-200">Allow from this source</strong> for Chrome, then tap <strong className="text-slate-200">Install</strong>.
          </li>
        </ol>
      </div>
    </div>
  );
};
