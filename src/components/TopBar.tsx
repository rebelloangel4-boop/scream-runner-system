import React from 'react';
import { Download, ShieldCheck, Gamepad2, LayoutDashboard, Terminal } from 'lucide-react';

interface TopBarProps {
  activeTab: 'dual' | 'game' | 'dashboard' | 'testing' | 'downloads';
  setActiveTab: (tab: 'dual' | 'game' | 'dashboard' | 'testing' | 'downloads') => void;
  backendOnline: boolean;
}

export const TopBar: React.FC<TopBarProps> = ({
  activeTab,
  setActiveTab,
  backendOnline,
}) => {
  return (
    <header className="flex items-center justify-between gap-8 px-6 py-3.5 bg-[#080B11]/90 backdrop-blur-md border-b border-slate-800/80 sticky top-0 z-50">
      {/* Zone 1: Single text element wordmark */}
      <div className="flex items-center gap-3 shrink-0">
        <a href="/" className="text-base font-bold tracking-tight text-white whitespace-nowrap hover:text-cyan-400 transition-colors">
          Scream Runner & Sam's Dashboard
        </a>
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs font-mono font-medium ${
            backendOnline ? 'bg-emerald-950/60 text-emerald-400 border border-emerald-800/50' : 'bg-red-950/60 text-red-400 border border-red-800/50'
          }`}
          title="Backend API & WebSocket Bus"
        >
          <span className={`w-1.5 h-1.5 rounded-full ${backendOnline ? 'bg-emerald-400' : 'bg-red-400 animate-pulse'}`} />
          {backendOnline ? 'FastAPI Online' : 'Reconnecting'}
        </span>
      </div>

      {/* Zone 2: 4–5 clean single-line text navigation links */}
      <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-400">
        <button
          onClick={() => setActiveTab('dual')}
          className={`hover:text-white transition-colors whitespace-nowrap shrink-0 flex items-center gap-1.5 ${
            activeTab === 'dual' ? 'text-cyan-400 font-semibold' : ''
          }`}
        >
          Dual Studio
        </button>
        <button
          onClick={() => setActiveTab('game')}
          className={`hover:text-white transition-colors whitespace-nowrap shrink-0 flex items-center gap-1.5 ${
            activeTab === 'game' ? 'text-cyan-400 font-semibold' : ''
          }`}
        >
          <Gamepad2 className="w-4 h-4" />
          Scream Runner
        </button>
        <button
          onClick={() => setActiveTab('dashboard')}
          className={`hover:text-white transition-colors whitespace-nowrap shrink-0 flex items-center gap-1.5 ${
            activeTab === 'dashboard' ? 'text-cyan-400 font-semibold' : ''
          }`}
        >
          <LayoutDashboard className="w-4 h-4" />
          Sam's Dashboard
        </button>
        <button
          onClick={() => setActiveTab('downloads')}
          className={`hover:text-white transition-colors whitespace-nowrap shrink-0 flex items-center gap-1.5 ${
            activeTab === 'downloads' ? 'text-cyan-400 font-semibold' : ''
          }`}
        >
          <Download className="w-4 h-4" />
          APK Downloads
        </button>
        <button
          onClick={() => setActiveTab('testing')}
          className={`hover:text-white transition-colors whitespace-nowrap shrink-0 flex items-center gap-1.5 ${
            activeTab === 'testing' ? 'text-cyan-400 font-semibold' : ''
          }`}
        >
          <Terminal className="w-4 h-4" />
          3-Round Tests
        </button>
      </nav>

      {/* Zone 3: Direct APK Download Action */}
      <div className="flex items-center gap-2 shrink-0">
        <button
          onClick={() => setActiveTab('downloads')}
          className="px-3.5 py-1.5 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-all flex items-center gap-1.5 whitespace-nowrap shrink-0 shadow-md shadow-cyan-500/20"
          title="Direct Installable APK Downloads (1.2 MB each)"
        >
          <Download className="w-4 h-4" />
          Download APKs (1.2 MB)
        </button>
      </div>
    </header>
  );
};
