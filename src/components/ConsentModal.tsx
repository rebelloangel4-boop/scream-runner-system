import React from 'react';
import { ShieldAlert, Mic, Clock, UploadCloud, CheckCircle2, X } from 'lucide-react';

interface ConsentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export const ConsentModal: React.FC<ConsentModalProps> = ({ isOpen, onClose, onConfirm }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-[#0F172A] border border-cyan-500/30 rounded-2xl max-w-lg w-full p-6 shadow-2xl relative text-slate-200">
        <button
          onClick={onClose}
          className="absolute top-4 right-4 text-slate-400 hover:text-white transition-colors"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-cyan-950/80 border border-cyan-500/40 flex items-center justify-center text-cyan-400">
            <ShieldAlert className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white tracking-tight">Authorized Smart Audio Recording</h2>
            <p className="text-xs text-slate-400">Explicit User Consent & Privacy Agreement</p>
          </div>
        </div>

        <div className="space-y-3.5 text-xs text-slate-300 leading-relaxed mb-6">
          <div className="flex items-start gap-2.5 p-3 rounded-lg bg-slate-900/60 border border-slate-800">
            <Mic className="w-4 h-4 text-cyan-400 shrink-0 mt-0.5" />
            <div>
              <strong className="text-white block font-medium">On-Device Voice Activity Detection (VAD)</strong>
              Continuous silence and ambient noise are discarded in memory. Only speech bursts are captured with a 300ms pre-speech buffer and 500ms post-speech buffer.
            </div>
          </div>

          <div className="flex items-start gap-2.5 p-3 rounded-lg bg-slate-900/60 border border-slate-800">
            <Clock className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
            <div>
              <strong className="text-white block font-medium">Foreground Service & 6-Hour Max Duration</strong>
              A persistent Android notification with a prominent STOP button remains active in your notification shade for full transparency and privacy compliance.
            </div>
          </div>

          <div className="flex items-start gap-2.5 p-3 rounded-lg bg-slate-900/60 border border-slate-800">
            <UploadCloud className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
            <div>
              <strong className="text-white block font-medium">Encrypted Storage & Resilient Synchronization</strong>
              Captured speech clips are stored locally first, deduplicated via cryptographic SHA-256 hashes, and synced to Sam's Dashboard backend.
            </div>
          </div>
        </div>

        <p className="text-[11px] text-slate-400 mb-6 bg-cyan-950/30 border border-cyan-800/40 p-2.5 rounded">
          By activating this feature, you explicitly authorize Scream Runner to capture, process, and synchronize authorized speech segments. You can tap <strong>STOP RECORDING</strong> at any moment.
        </p>

        <div className="flex items-center justify-end gap-3">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-slate-300 hover:text-white bg-slate-800 rounded-lg hover:bg-slate-700 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="px-4 py-2 text-xs font-semibold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-all flex items-center gap-1.5 shadow-lg shadow-cyan-500/20"
          >
            <CheckCircle2 className="w-4 h-4" />
            I Consent & Activate Session
          </button>
        </div>
      </div>
    </div>
  );
};
