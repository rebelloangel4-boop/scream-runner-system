import React, { useState } from 'react';
import { Smartphone, CheckCircle2 } from 'lucide-react';
import { usePWAInstall } from '../usePWAInstall';

export const PWAInstallButton: React.FC = () => {
  const { isInstallable, isInstalled, isIOS, install } = usePWAInstall();
  const [showGuide, setShowGuide] = useState(false);

  if (isInstalled) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-emerald-950/80 border border-emerald-700/60 text-emerald-300 text-xs font-semibold">
        <CheckCircle2 className="w-3.5 h-3.5" />
        Installed on Device
      </span>
    );
  }

  return (
    <>
      <button
        onClick={async () => {
          if (isInstallable) {
            await install();
          } else {
            setShowGuide(true);
          }
        }}
        className="px-3.5 py-1.5 text-xs font-bold text-[#080B11] bg-emerald-400 hover:bg-emerald-300 rounded-lg transition-all flex items-center gap-1.5 whitespace-nowrap shrink-0 shadow-md shadow-emerald-500/20"
        title="Install directly via Google Chrome WebAPK with ZERO Play Protect warnings"
      >
        <Smartphone className="w-4 h-4" />
        1-Tap Install (No Risk Warning)
      </button>

      {showGuide && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="w-full max-w-md rounded-2xl bg-[#0F172A] border border-emerald-500/40 p-6 shadow-2xl text-slate-200">
            <h3 className="text-base font-bold text-emerald-400 mb-2">
              Zero-Warning Android Installation (Google WebAPK)
            </h3>
            <p className="text-xs text-slate-300 leading-relaxed mb-4">
              Android flags <strong>all</strong> raw <code className="text-cyan-300">.apk</code> files downloaded outside the Google Play Store with an "Unknown developer / Risk" prompt. To install on your Android phone with <strong>zero security warnings</strong>:
            </p>
            {isIOS ? (
              <ol className="list-decimal list-inside space-y-2 text-xs text-slate-300 mb-5">
                <li>Tap the <strong>Share</strong> icon in Safari.</li>
                <li>Tap <strong>Add to Home Screen</strong>.</li>
              </ol>
            ) : (
              <ol className="list-decimal list-inside space-y-2 text-xs text-slate-300 mb-5">
                <li>
                  Open the shared app link directly in <strong>Android Chrome</strong>:{' '}
                  <a
                    href="https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-cyan-400 underline break-all"
                  >
                    https://ais-pre-3fyydrqqukwg2upmm34wb7-344668128828.asia-northeast1.run.app
                  </a>
                </li>
                <li>
                  Tap the <strong>⋮ (three dots menu)</strong> in the top-right corner of Chrome.
                </li>
                <li>
                  Tap <strong>"Add to Home screen" → "Install"</strong>. Chrome's official Google WebAPK service signs and installs the app onto your phone with <strong>0 risk warnings</strong>!
                </li>
              </ol>
            )}
            <button
              onClick={() => setShowGuide(false)}
              className="w-full rounded-xl bg-emerald-400 hover:bg-emerald-300 py-2 text-xs font-bold text-[#080B11]"
            >
              Got It
            </button>
          </div>
        </div>
      )}
    </>
  );
};
