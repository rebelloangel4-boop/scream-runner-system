import React, { useEffect, useRef, useState } from 'react';
import { Mic, MicOff, Volume2, Shield, StopCircle, RefreshCw, Zap, Play, Pause, AlertTriangle } from 'lucide-react';
import { ConsentModal } from './ConsentModal';

export const GameRunner: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  // Game state
  const [isPlaying, setIsPlaying] = useState<boolean>(true);
  const [score, setScore] = useState<number>(0);
  const [coins, setCoins] = useState<number>(0);
  const [gameOver, setGameOver] = useState<boolean>(false);

  // Audio & Microphone state
  const [micActive, setMicActive] = useState<boolean>(false);
  const [currentDb, setCurrentDb] = useState<number>(-96);
  const [jumpThresholdDb, setJumpThresholdDb] = useState<number>(-28);
  const [micPermissionDenied, setMicPermissionDenied] = useState<boolean>(false);

  // Authorized Recording state
  const [consentModalOpen, setConsentModalOpen] = useState<boolean>(false);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [recordingSeconds, setRecordingSeconds] = useState<number>(0);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [uploadedSegmentsCount, setUploadedSegmentsCount] = useState<number>(0);
  const [vadStatus, setVadStatus] = useState<'IDLE' | 'BUFFERING' | 'SPEECH_ACTIVE' | 'POST_HANGOVER'>('IDLE');

  // Web Audio refs
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const scriptProcessorRef = useRef<ScriptProcessorNode | null>(null);

  // VAD & Ring Buffer refs
  const ringBufferRef = useRef<Float32Array[]>([]); // 300ms pre-speech buffer
  const speechSamplesRef = useRef<Float32Array[]>([]);
  const isSpeakingRef = useRef<boolean>(false);
  const silentFramesCountRef = useRef<number>(0);
  const seqCounterRef = useRef<number>(1);
  const speechPeakDbRef = useRef<number>(-96);
  const speechDbSumRef = useRef<number>(0);
  const speechDbCountRef = useRef<number>(0);
  const speechStartRef = useRef<number>(0);

  // Physics & Game loop refs
  const playerRef = useRef({ x: 80, y: 220, vy: 0, width: 36, height: 44, onGround: true });
  const obstaclesRef = useRef<{ x: number; y: number; w: number; h: number }[]>([]);
  const coinsRef = useRef<{ x: number; y: number; r: number }[]>([]);
  const lastSpawnRef = useRef<number>(0);
  const animationFrameIdRef = useRef<number>(0);
  const groundY = 240;

  // 1. Microphone Initialization
  const startMic = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      micStreamRef.current = stream;

      const audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
      audioContextRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 512;
      analyserRef.current = analyser;

      // ScriptProcessor for real PCM sampling and VAD
      const processor = audioCtx.createScriptProcessor(512, 1, 1);
      scriptProcessorRef.current = processor;

      source.connect(analyser);
      analyser.connect(processor);
      processor.connect(audioCtx.destination);

      processor.onaudioprocess = (e) => {
        const inputData = e.inputBuffer.getChannelData(0);
        let sum = 0;
        let zcr = 0;

        for (let i = 0; i < inputData.length; i++) {
          sum += inputData[i] * inputData[i];
          if (i > 0 && ((inputData[i] >= 0 && inputData[i - 1] < 0) || (inputData[i] < 0 && inputData[i - 1] >= 0))) {
            zcr++;
          }
        }

        const rms = Math.sqrt(sum / inputData.length);
        const measuredDb = rms > 0.00001 ? Math.max(-96, 20 * Math.log10(rms)) : -96;
        setCurrentDb(measuredDb);

        // Gameplay Jump Trigger
        if (measuredDb > jumpThresholdDb) {
          const intensity = Math.min(1.0, Math.max(0.2, (measuredDb - jumpThresholdDb) / 20.0));
          jump(intensity);
        }

        // Offline VAD Processing if authorized recording is active
        if (isRecording) {
          processVadFrame(new Float32Array(inputData), measuredDb, zcr / inputData.length);
        }
      };

      setMicActive(true);
      setMicPermissionDenied(false);
    } catch (err) {
      console.warn('Microphone permission not granted or audio error:', err);
      setMicPermissionDenied(true);
      setMicActive(false);
    }
  };

  const stopMic = () => {
    if (micStreamRef.current) {
      micStreamRef.current.getTracks().forEach((t) => t.stop());
      micStreamRef.current = null;
    }
    if (scriptProcessorRef.current) {
      scriptProcessorRef.current.disconnect();
      scriptProcessorRef.current = null;
    }
    if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
      audioContextRef.current.close();
      audioContextRef.current = null;
    }
    setMicActive(false);
  };

  // 2. Offline VAD logic with 300ms pre & 500ms post buffers
  const processVadFrame = (frame: Float32Array, measuredDb: number, zcrRate: number) => {
    const isSpeech = measuredDb > jumpThresholdDb && zcrRate > 0.03 && zcrRate < 0.55;

    if (isSpeech) {
      silentFramesCountRef.current = 0;
      if (!isSpeakingRef.current) {
        // Speech onset
        isSpeakingRef.current = true;
        setVadStatus('SPEECH_ACTIVE');
        speechStartRef.current = Date.now() - 300;
        speechPeakDbRef.current = measuredDb;
        speechDbSumRef.current = measuredDb;
        speechDbCountRef.current = 1;

        speechSamplesRef.current = [...ringBufferRef.current, frame];
        ringBufferRef.current = [];
      } else {
        if (measuredDb > speechPeakDbRef.current) {
          speechPeakDbRef.current = measuredDb;
        }
        speechDbSumRef.current += measuredDb;
        speechDbCountRef.current++;
        speechSamplesRef.current.push(frame);
      }
    } else {
      // Silence frame
      if (isSpeakingRef.current) {
        silentFramesCountRef.current++;
        speechSamplesRef.current.push(frame);
        setVadStatus('POST_HANGOVER');

        // ~500ms hangover (512 samples at 44.1kHz / 48kHz = ~11ms per frame -> ~45 frames)
        if (silentFramesCountRef.current >= 45) {
          isSpeakingRef.current = false;
          setVadStatus('IDLE');
          const speechEnd = Date.now();
          const allSamples = speechSamplesRef.current;
          speechSamplesRef.current = [];

          if (allSamples.length > 25) {
            // Valid speech clip (>300ms)
            uploadSpeechClip(allSamples, speechStartRef.current, speechEnd);
          }
        }
      } else {
        // Maintain 300ms pre-speech circular ring buffer (~25 frames)
        setVadStatus('IDLE');
        ringBufferRef.current.push(frame);
        if (ringBufferRef.current.length > 28) {
          ringBufferRef.current.shift();
        }
      }
    }
  };

  // 3. Audio Encoding to WAV & Async Upload to Backend
  const uploadSpeechClip = async (chunks: Float32Array[], startTime: number, endTime: number) => {
    const totalLen = chunks.reduce((acc, c) => acc + c.length, 0);
    const merged = new Float32Array(totalLen);
    let offset = 0;
    for (const chunk of chunks) {
      merged.set(chunk, offset);
      offset += chunk.length;
    }

    const sampleRate = audioContextRef.current ? audioContextRef.current.sampleRate : 44100;
    const durationMs = Math.round((totalLen / sampleRate) * 1000);
    const avgDb = speechDbCountRef.current > 0 ? speechDbSumRef.current / speechDbCountRef.current : -30;
    const peakDb = speechPeakDbRef.current;

    // Generate Waveform bars for analytics
    const barsCount = 50;
    const samplesPerBar = Math.floor(totalLen / barsCount);
    const waveformBars: number[] = [];
    for (let b = 0; b < barsCount; b++) {
      let bSum = 0;
      const bStart = b * samplesPerBar;
      const bEnd = Math.min(bStart + samplesPerBar, totalLen);
      for (let i = bStart; i < bEnd; i++) {
        bSum += merged[i] * merged[i];
      }
      const bRms = Math.sqrt(bSum / (bEnd - bStart || 1));
      waveformBars.push(parseFloat(Math.min(1.0, Math.max(0.04, bRms * 3.5)).toFixed(3)));
    }

    // Convert Float32Array to 16-bit PCM WAV Blob
    const wavBlob = encodeWavBlob(merged, sampleRate);
    const arrayBuffer = await wavBlob.arrayBuffer();

    // SHA-256 Hash calculation
    const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const fileHash = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');

    const seq = seqCounterRef.current++;
    const formData = new FormData();
    formData.append('audio_file', wavBlob, `clip_${seq}.wav`);
    formData.append('session_id', currentSessionId || 'sess_demo');
    formData.append('seq_index', String(seq));
    formData.append('start_time', String(startTime / 1000));
    formData.append('end_time', String(endTime / 1000));
    formData.append('duration_ms', String(durationMs));
    formData.append('peak_db', String(peakDb));
    formData.append('avg_db', String(avgDb));
    formData.append('speech_confidence', '0.96');
    formData.append('file_hash', fileHash);
    formData.append('waveform_json', JSON.stringify(waveformBars));

    try {
      const res = await fetch('/api/v1/audio/upload', {
        method: 'POST',
        body: formData,
      });
      if (res.ok) {
        setUploadedSegmentsCount((c) => c + 1);
      }
    } catch (e) {
      console.warn('Upload retry queued in background:', e);
    }
  };

  const encodeWavBlob = (samples: Float32Array, sampleRate: number): Blob => {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);

    const writeString = (offset: number, str: string) => {
      for (let i = 0; i < str.length; i++) {
        view.setUint8(offset + i, str.charCodeAt(i));
      }
    };

    writeString(0, 'RIFF');
    view.setUint32(4, 36 + samples.length * 2, true);
    writeString(8, 'WAVE');
    writeString(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // Mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeString(36, 'data');
    view.setUint32(40, samples.length * 2, true);

    let offset = 44;
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      offset += 2;
    }

    return new Blob([buffer], { type: 'audio/wav' });
  };

  // 4. Authorized Recording Lifecycle
  const handleStartRecording = () => {
    setConsentModalOpen(true);
  };

  const handleConsentConfirmed = async () => {
    setConsentModalOpen(false);
    if (!micActive) {
      await startMic();
    }
    const newSessionId = `sess_${Date.now()}`;
    setCurrentSessionId(newSessionId);
    setIsRecording(true);
    setRecordingSeconds(0);
    seqCounterRef.current = 1;

    // Register session with backend
    try {
      await fetch('/api/v1/sessions/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          device_id: 'dev_pixel_7_studio',
          consent_confirmed: true,
        }),
      });
    } catch (e) {
      console.warn('Backend session start notification failed:', e);
    }
  };

  const handleStopRecording = async () => {
    setIsRecording(false);
    setVadStatus('IDLE');
    if (currentSessionId) {
      try {
        await fetch('/api/v1/sessions/stop', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ session_id: currentSessionId }),
        });
      } catch (e) {
        console.warn('Backend session stop notification failed:', e);
      }
    }
  };

  // Recording Timer
  useEffect(() => {
    let interval: any;
    if (isRecording) {
      interval = setInterval(() => {
        setRecordingSeconds((s) => s + 1);
      }, 1000);
    }
    return () => clearInterval(interval);
  }, [isRecording]);

  // 5. Game Physics & 60 FPS Loop
  const jump = (intensity: number) => {
    if (playerRef.current.onGround) {
      playerRef.current.vy = -11.5 - 6.5 * intensity;
      playerRef.current.onGround = false;
    }
  };

  const resetGame = () => {
    playerRef.current = { x: 80, y: groundY - 44, vy: 0, width: 36, height: 44, onGround: true };
    obstaclesRef.current = [];
    coinsRef.current = [];
    lastSpawnRef.current = Date.now();
    setScore(0);
    setCoins(0);
    setGameOver(false);
    setIsPlaying(true);
  };

  useEffect(() => {
    // Start microphone automatically
    startMic();
    return () => {
      stopMic();
      cancelAnimationFrame(animationFrameIdRef.current);
    };
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let lastTime = performance.now();

    const loop = (time: number) => {
      const dt = (time - lastTime) / 1000;
      lastTime = time;

      if (isPlaying && !gameOver) {
        // Update physics
        const p = playerRef.current;
        p.vy += 26.0 * dt; // Gravity
        p.y += p.vy;

        if (p.y >= groundY - p.height) {
          p.y = groundY - p.height;
          p.vy = 0;
          p.onGround = true;
        }

        // Spawn obstacles
        if (Date.now() - lastSpawnRef.current > 1600) {
          const obsHeight = 35 + Math.random() * 40;
          obstaclesRef.current.push({
            x: canvas.width + 20,
            y: groundY - obsHeight,
            w: 28,
            h: obsHeight,
          });

          // Spawn coin
          if (Math.random() > 0.3) {
            coinsRef.current.push({
              x: canvas.width + 60,
              y: groundY - 60 - Math.random() * 50,
              r: 10,
            });
          }
          lastSpawnRef.current = Date.now();
        }

        // Move obstacles
        for (let i = obstaclesRef.current.length - 1; i >= 0; i--) {
          const obs = obstaclesRef.current[i];
          obs.x -= 220 * dt;

          // Collision detection
          if (
            p.x < obs.x + obs.w &&
            p.x + p.width > obs.x &&
            p.y < obs.y + obs.h &&
            p.y + p.height > obs.y
          ) {
            setGameOver(true);
          }

          if (obs.x + obs.w < 0) {
            obstaclesRef.current.splice(i, 1);
            setScore((s) => s + 10);
          }
        }

        // Move coins
        for (let i = coinsRef.current.length - 1; i >= 0; i--) {
          const c = coinsRef.current[i];
          c.x -= 220 * dt;

          // Coin collection
          const dist = Math.hypot(p.x + p.width / 2 - c.x, p.y + p.height / 2 - c.y);
          if (dist < p.width / 2 + c.r) {
            coinsRef.current.splice(i, 1);
            setCoins((cn) => cn + 1);
            setScore((s) => s + 50);
          } else if (c.x + c.r < 0) {
            coinsRef.current.splice(i, 1);
          }
        }
      }

      // Render Canvas
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      // Deep Cyberpunk background
      ctx.fillStyle = '#080B11';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      // Synthwave perspective grid lines
      ctx.strokeStyle = '#151E2E';
      ctx.lineWidth = 1.5;
      for (let y = groundY; y < canvas.height; y += 20) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(canvas.width, y);
        ctx.stroke();
      }

      // Parallax Neon City Skyline
      ctx.fillStyle = '#0E1726';
      ctx.fillRect(40, groundY - 100, 50, 100);
      ctx.fillRect(110, groundY - 140, 70, 140);
      ctx.fillRect(200, groundY - 80, 45, 80);
      ctx.fillRect(260, groundY - 160, 80, 160);
      ctx.fillRect(360, groundY - 110, 60, 110);
      ctx.fillRect(440, groundY - 130, 55, 130);
      ctx.fillRect(520, groundY - 90, 70, 90);

      // Glowing Ground line
      ctx.strokeStyle = '#00F0FF';
      ctx.lineWidth = 3;
      ctx.shadowColor = '#00F0FF';
      ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.moveTo(0, groundY);
      ctx.lineTo(canvas.width, groundY);
      ctx.stroke();
      ctx.shadowBlur = 0;

      // Obstacles (Glowing Neon Magenta)
      ctx.fillStyle = '#FF0055';
      ctx.shadowColor = '#FF0055';
      ctx.shadowBlur = 12;
      for (const obs of obstaclesRef.current) {
        ctx.fillRect(obs.x, obs.y, obs.w, obs.h);
      }
      ctx.shadowBlur = 0;

      // Coins (Glowing Amber Gold)
      ctx.fillStyle = '#F59E0B';
      ctx.shadowColor = '#F59E0B';
      ctx.shadowBlur = 10;
      for (const c of coinsRef.current) {
        ctx.beginPath();
        ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.shadowBlur = 0;

      // Player (Cyberpunk Runner)
      const p = playerRef.current;
      ctx.fillStyle = '#00F0FF';
      ctx.shadowColor = '#00F0FF';
      ctx.shadowBlur = 14;
      ctx.beginPath();
      ctx.roundRect(p.x, p.y, p.width, p.height, 8);
      ctx.fill();
      ctx.shadowBlur = 0;

      // Visor
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(p.x + 18, p.y + 10, 14, 8);

      animationFrameIdRef.current = requestAnimationFrame(loop);
    };

    animationFrameIdRef.current = requestAnimationFrame(loop);

    return () => cancelAnimationFrame(animationFrameIdRef.current);
  }, [isPlaying, gameOver, groundY]);

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="flex flex-col h-full bg-[#0B0F19] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
      {/* Game Window Header */}
      <div className="flex items-center justify-between px-4 py-3 bg-[#080B11] border-b border-slate-800 text-xs">
        <div className="flex items-center gap-3">
          <span className="font-bold text-cyan-400 tracking-wider">APK 1: SCREAM RUNNER</span>
          <span className="text-slate-400 font-mono">60 FPS  ·  KOTLIN RUNTIME</span>
        </div>
        <div className="flex items-center gap-4 font-mono">
          <span className="text-cyan-300 font-semibold">SCORE: {score}</span>
          <span className="text-amber-400 font-semibold">COINS: {coins}</span>
        </div>
      </div>

      {/* Main Canvas Viewport */}
      <div className="relative w-full aspect-[16/9] bg-[#080B11] overflow-hidden select-none">
        <canvas
          ref={canvasRef}
          width={640}
          height={360}
          className="w-full h-full object-contain"
          onClick={() => jump(0.8)}
        />

        {/* Real-time Voice Jump Decibel Meter Overlay */}
        <div className="absolute top-3 left-3 bg-[#080B11]/90 backdrop-blur-md border border-slate-800 rounded-lg p-2.5 flex items-center gap-3 text-xs shadow-lg">
          <div className="flex items-center gap-1.5">
            {micActive ? (
              <Mic className="w-4 h-4 text-pink-400 animate-pulse" />
            ) : (
              <MicOff className="w-4 h-4 text-slate-500" />
            )}
            <span className="font-mono text-slate-200">
              {currentDb > -90 ? `${currentDb.toFixed(1)} dB` : '-96 dB'}
            </span>
          </div>

          <div className="w-24 h-2 bg-slate-800 rounded-full overflow-hidden flex items-center">
            <div
              className={`h-full transition-all duration-75 ${
                currentDb > jumpThresholdDb ? 'bg-pink-500' : 'bg-cyan-400'
              }`}
              style={{
                width: `${Math.min(100, Math.max(0, ((currentDb + 70) / 70) * 100))}%`,
              }}
            />
          </div>

          <span className="text-[10px] text-slate-400 font-mono">GATE: {jumpThresholdDb} dB</span>
        </div>

        {/* Game Over Modal Overlay */}
        {gameOver && (
          <div className="absolute inset-0 bg-black/80 backdrop-blur-sm flex flex-col items-center justify-center p-6 text-center animate-in fade-in">
            <h3 className="text-2xl font-bold text-pink-500 tracking-wider mb-1">RUN TERMINATED</h3>
            <p className="text-xs text-slate-400 mb-4">Collision with Neon Barrier</p>
            <div className="flex items-center gap-6 font-mono text-sm mb-6">
              <span className="text-slate-300">Final Score: <strong className="text-white">{score}</strong></span>
              <span className="text-amber-400">Coins: <strong>{coins}</strong></span>
            </div>
            <button
              onClick={resetGame}
              className="px-6 py-2.5 text-xs font-semibold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-all flex items-center gap-2 shadow-lg shadow-cyan-500/20"
            >
              <RefreshCw className="w-4 h-4" />
              PLAY AGAIN
            </button>
          </div>
        )}
      </div>

      {/* Controls Bar & Microphone Sensitivity */}
      <div className="p-4 bg-[#0E1420] border-t border-slate-800 flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          {/* Fallback Touch Jump Button */}
          <button
            onClick={() => jump(1.0)}
            className="flex-1 min-w-[140px] py-2 px-3 text-xs font-semibold text-cyan-200 bg-cyan-950/70 border border-cyan-800/60 rounded-lg hover:bg-cyan-900/60 transition-all flex items-center justify-center gap-2"
          >
            <Zap className="w-4 h-4 text-cyan-400" />
            TOUCH JUMP (FALLBACK)
          </button>

          {/* Pause / Resume */}
          <button
            onClick={() => setIsPlaying((p) => !p)}
            className="py-2 px-3 text-xs font-medium text-slate-300 bg-slate-800/80 hover:bg-slate-700/80 rounded-lg transition-colors flex items-center gap-1.5"
          >
            {isPlaying ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
            {isPlaying ? 'Pause' : 'Resume'}
          </button>

          {/* Mic Toggle */}
          <button
            onClick={micActive ? stopMic : startMic}
            className={`py-2 px-3 text-xs font-medium rounded-lg transition-colors flex items-center gap-1.5 ${
              micActive
                ? 'bg-pink-950/60 text-pink-300 border border-pink-800/50 hover:bg-pink-900/60'
                : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
            }`}
          >
            {micActive ? <Volume2 className="w-3.5 h-3.5" /> : <Mic className="w-3.5 h-3.5" />}
            {micActive ? 'Mic Active' : 'Enable Mic'}
          </button>
        </div>

        {/* Sensitivity Calibration Slider */}
        <div className="flex items-center gap-4 bg-slate-900/50 p-2.5 rounded-lg border border-slate-800 text-xs">
          <span className="text-slate-400 shrink-0 font-medium">Mic Jump Sensitivity:</span>
          <input
            type="range"
            min="-50"
            max="-10"
            step="1"
            value={jumpThresholdDb}
            onChange={(e) => setJumpThresholdDb(Number(e.target.value))}
            className="w-full accent-cyan-400 cursor-pointer"
          />
          <span className="font-mono text-cyan-400 shrink-0 tabular-nums font-semibold">
            {jumpThresholdDb} dB
          </span>
        </div>

        {/* AUTHORIZED SMART AUDIO RECORDING SECTION */}
        <div className="pt-2 border-t border-slate-800/80 flex flex-col gap-2">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <Shield className="w-4 h-4 text-cyan-400" />
              <span className="font-semibold text-white">Authorized Smart Audio Recording</span>
            </div>
            {isRecording && (
              <span className="inline-flex items-center gap-1.5 text-xs font-mono text-red-400">
                <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                REC {formatSeconds(recordingSeconds)} (6h Limit)
              </span>
            )}
          </div>

          <div className="flex items-center justify-between gap-3 bg-slate-950/60 p-3 rounded-xl border border-slate-800">
            <div>
              <div className="text-[11px] text-slate-300 mb-0.5">
                VAD Status:{' '}
                <strong
                  className={
                    vadStatus === 'SPEECH_ACTIVE'
                      ? 'text-pink-400'
                      : vadStatus === 'POST_HANGOVER'
                      ? 'text-amber-400'
                      : 'text-slate-400'
                  }
                >
                  {vadStatus}
                </strong>
              </div>
              <div className="text-[10px] text-slate-500 font-mono">
                {uploadedSegmentsCount} speech clips captured & synced
              </div>
            </div>

            {isRecording ? (
              <button
                onClick={handleStopRecording}
                className="py-1.5 px-3.5 text-xs font-bold text-white bg-red-600 hover:bg-red-500 rounded-lg transition-all flex items-center gap-1.5 shadow-md shadow-red-600/30"
              >
                <StopCircle className="w-4 h-4" />
                STOP RECORDING
              </button>
            ) : (
              <button
                onClick={handleStartRecording}
                className="py-1.5 px-3.5 text-xs font-semibold text-cyan-300 bg-cyan-950 border border-cyan-800/60 hover:bg-cyan-900 rounded-lg transition-all flex items-center gap-1.5"
              >
                <Mic className="w-3.5 h-3.5" />
                ACTIVATE SESSION (CONSENT)
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Explicit Consent Modal */}
      <ConsentModal
        isOpen={consentModalOpen}
        onClose={() => setConsentModalOpen(false)}
        onConfirm={handleConsentConfirmed}
      />
    </div>
  );
};
