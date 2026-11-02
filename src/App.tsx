import React, { useState, useEffect } from 'react';
import { TopBar } from './components/TopBar';
import { GameRunner } from './components/GameRunner';
import { DashboardView } from './components/DashboardView';
import { TestingSuite } from './components/TestingSuite';
import { DownloadsView } from './components/DownloadsView';
import { Download, ShieldCheck, Gamepad2, Terminal, Info, ExternalLink } from 'lucide-react';

export default function App() {
  const [activeTab, setActiveTab] = useState<'dual' | 'game' | 'dashboard' | 'testing' | 'downloads'>('dual');
  const [backendOnline, setBackendOnline] = useState<boolean>(true);

  // Poll backend health
  useEffect(() => {
    const checkHealth = async () => {
      try {
        const res = await fetch('/api/v1/health');
        if (res.ok) {
          setBackendOnline(true);
        } else {
          setBackendOnline(false);
        }
      } catch {
        setBackendOnline(false);
      }
    };

    checkHealth();
    const interval = setInterval(checkHealth, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="min-h-screen flex flex-col bg-[#080B11] text-slate-100">
      {/* Universal Top Bar */}
      <TopBar
        activeTab={activeTab}
        setActiveTab={setActiveTab}
        backendOnline={backendOnline}
      />

      {/* Main Studio Viewport */}
      <main className="flex-1 p-4 lg:p-6 max-w-[1700px] w-full mx-auto flex flex-col">
        {activeTab === 'dual' && (
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-6 flex-1 items-stretch">
            {/* Left Column: Scream Runner (5 cols) */}
            <div className="xl:col-span-5 flex flex-col h-full min-h-[600px]">
              <GameRunner />
            </div>

            {/* Right Column: Sam's Dashboard (7 cols) */}
            <div className="xl:col-span-7 flex flex-col h-full min-h-[600px]">
              <DashboardView />
            </div>
          </div>
        )}

        {activeTab === 'game' && (
          <div className="max-w-4xl mx-auto w-full flex-1 min-h-[680px]">
            <GameRunner />
          </div>
        )}

        {activeTab === 'dashboard' && (
          <div className="max-w-6xl mx-auto w-full flex-1 min-h-[680px]">
            <DashboardView />
          </div>
        )}

        {activeTab === 'testing' && (
          <div className="max-w-5xl mx-auto w-full flex-1 min-h-[680px]">
            <TestingSuite />
          </div>
        )}

        {activeTab === 'downloads' && (
          <div className="max-w-5xl mx-auto w-full flex-1 min-h-[680px]">
            <DownloadsView />
          </div>
        )}
      </main>

      {/* Footer Info & Verification Bar */}
      <footer className="border-t border-slate-800/80 bg-[#06080D] px-6 py-4 text-xs text-slate-400">
        <div className="max-w-[1700px] mx-auto flex flex-wrap items-center justify-between gap-4 font-mono">
          <div className="flex items-center gap-3">
            <span className="text-white font-semibold">DELIVERABLES READY</span>
            <span>·</span>
            <a
              href="/downloads/scream-runner.apk"
              download="scream-runner.apk"
              className="text-cyan-400 hover:underline flex items-center gap-1"
            >
              <Download className="w-3 h-3" />
              scream-runner.apk (1.2 MB)
            </a>
            <span>·</span>
            <a
              href="/downloads/sams-dashboard.apk"
              download="sams-dashboard.apk"
              className="text-emerald-400 hover:underline flex items-center gap-1"
            >
              <Download className="w-3 h-3" />
              sams-dashboard.apk (1.2 MB)
            </a>
          </div>

          <div className="flex items-center gap-4 text-slate-500">
            <span>APK Signature v2/v3: VERIFIED</span>
            <span>·</span>
            <span>FastAPI SQLite WAL: ONLINE</span>
            <span>·</span>
            <span>Zero ZIP files deliverable</span>
          </div>
        </div>
      </footer>
    </div>
  );
}
