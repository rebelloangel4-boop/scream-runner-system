import React, { useRef } from 'react';

interface AudioWaveformProps {
  amplitudes: number[];
  progress: number; // 0.0 to 1.0
  onScrub: (progress: number) => void;
  peakDb: number;
  isHighlight?: boolean;
}

export const AudioWaveform: React.FC<AudioWaveformProps> = ({
  amplitudes,
  progress,
  onScrub,
  peakDb,
  isHighlight = false,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);

  const handleClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const newProgress = Math.max(0, Math.min(1, clickX / rect.width));
    onScrub(newProgress);
  };

  const bars = amplitudes && amplitudes.length > 0 ? amplitudes : Array.from({ length: 50 }, (_, i) => Math.sin(i * 0.15) * 0.4 + 0.3);

  return (
    <div
      ref={containerRef}
      onClick={handleClick}
      className="relative w-full h-24 bg-slate-950/80 border border-slate-800 rounded-xl p-2 cursor-pointer select-none group flex items-center justify-between gap-1 overflow-hidden"
    >
      {/* Waveform bars */}
      {bars.map((amp, index) => {
        const barHeight = Math.max(8, amp * 80);
        const barRatio = index / bars.length;
        const isPast = barRatio <= progress;
        const isPeak = isHighlight && amp > 0.65;

        return (
          <div
            key={index}
            className="flex-1 flex flex-col justify-center items-center h-full transition-all duration-75"
          >
            <div
              style={{ height: `${barHeight}%` }}
              className={`w-full rounded-full transition-colors duration-100 ${
                isPast
                  ? isPeak
                    ? 'bg-pink-500 shadow-[0_0_8px_rgba(236,72,153,0.8)]'
                    : 'bg-cyan-400 shadow-[0_0_6px_rgba(0,240,255,0.6)]'
                  : isPeak
                  ? 'bg-pink-900/60'
                  : 'bg-slate-700 hover:bg-slate-500'
              }`}
            />
          </div>
        );
      })}

      {/* Scrub Playhead line */}
      <div
        className="absolute top-0 bottom-0 w-0.5 bg-white shadow-[0_0_8px_#ffffff] pointer-events-none transition-all duration-75"
        style={{ left: `${progress * 100}%` }}
      />

      {/* Overlay Peak Decibel Badge */}
      <div className="absolute top-2 right-2 px-2 py-0.5 rounded bg-slate-900/90 border border-slate-700/80 text-[10px] font-mono text-slate-300 pointer-events-none">
        Peak: <span className={peakDb > -10 ? 'text-pink-400 font-bold' : 'text-cyan-400'}>{peakDb.toFixed(1)} dB</span>
      </div>
    </div>
  );
};
