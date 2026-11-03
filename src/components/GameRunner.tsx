import React, { useEffect, useRef, useState } from 'react';
import {
  Mic,
  MicOff,
  Volume2,
  VolumeX,
  Shield,
  StopCircle,
  RefreshCw,
  Zap,
  Play,
  Pause,
  Sliders
} from 'lucide-react';
import { ConsentModal } from './ConsentModal';

interface Obstacle {
  x: number;
  y: number;
  w: number;
  h: number;
  type: 'ground' | 'drone' | 'double';
}

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  alpha: number;
  color: string;
}

export const GameRunner: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  // Game states: 'MENU' | 'PLAYING' | 'PAUSED' | 'GAMEOVER'
  const [gameState, setGameState] = useState<'MENU' | 'PLAYING' | 'PAUSED' | 'GAMEOVER'>('PLAYING');
  const [score, setScore] = useState<number>(0);
  const [highScore, setHighScore] = useState<number>(() => Number(localStorage.getItem('sr_high_score') || '0'));
  const [coins, setCoins] = useState<number>(0);
  const [fps, setFps] = useState<number>(60);
  const [frameTimeMs, setFrameTimeMs] = useState<number>(16.6);
  const [sfxMuted, setSfxMuted] = useState<boolean>(false);

  // Audio & Microphone state (Strict Opt-In: never starts mic automatically on mount)
  const [micActive, setMicActive] = useState<boolean>(false);
  const [currentDb, setCurrentDb] = useState<number>(-96);
  const [noiseFloorDb, setNoiseFloorDb] = useState<number>(-55);
  const [jumpThresholdDb, setJumpThresholdDb] = useState<number>(-28);
  const [micPermissionDenied, setMicPermissionDenied] = useState<boolean>(false);

  // Authorized Recording state
  const [consentModalOpen, setConsentModalOpen] = useState<boolean>(false);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [recordingSeconds, setRecordingSeconds] = useState<number>(0);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [uploadedSegmentsCount, setUploadedSegmentsCount] = useState<number>(0);
  const [queuedRetriesCount, setQueuedRetriesCount] = useState<number>(0);
  const [vadStatus, setVadStatus] = useState<'IDLE' | 'SPEECH_ACTIVE' | 'POST_HANGOVER'>('IDLE');
  const [deviceToken, setDeviceToken] = useState<string | null>(localStorage.getItem('sr_device_token'));

  // Refs for real-time audio & game loop
  const isRecordingRef = useRef<boolean>(false);
  const currentSessionIdRef = useRef<string | null>(null);
  const jumpThresholdDbRef = useRef<number>(-28);
  const sfxMutedRef = useRef<boolean>(false);
  const deviceTokenRef = useRef<string | null>(localStorage.getItem('sr_device_token'));

  useEffect(() => {
    isRecordingRef.current = isRecording;
  }, [isRecording]);

  useEffect(() => {
    currentSessionIdRef.current = currentSessionId;
  }, [currentSessionId]);

  useEffect(() => {
    jumpThresholdDbRef.current = jumpThresholdDb;
  }, [jumpThresholdDb]);

  useEffect(() => {
    sfxMutedRef.current = sfxMuted;
  }, [sfxMuted]);

  useEffect(() => {
    deviceTokenRef.current = deviceToken;
  }, [deviceToken]);

  // Web Audio refs
  const audioContextRef = useRef<AudioContext | null>(null);
  const sfxContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const scriptProcessorRef = useRef<ScriptProcessorNode | null>(null);

  // VAD & Ring Buffer refs (300ms pre-roll, 500ms post-roll)
  const ringBufferRef = useRef<Float32Array[]>([]);
  const speechSamplesRef = useRef<Float32Array[]>([]);
  const isSpeakingRef = useRef<boolean>(false);
  const silentFramesCountRef = useRef<number>(0);
  const seqCounterRef = useRef<number>(1);
  const speechPeakDbRef = useRef<number>(-96);
  const speechDbSumRef = useRef<number>(0);
  const speechDbCountRef = useRef<number>(0);
  const speechStartRef = useRef<number>(0);
  const pendingUploadsRef = useRef<FormData[]>([]);

  // Physics & Game loop refs
  const playerRef = useRef({
    x: 80,
    y: 196,
    vy: 0,
    width: 36,
    height: 44,
    onGround: true,
    hasShield: false,
  });
  const obstaclesRef = useRef<Obstacle[]>([]);
  const coinsRef = useRef<{ x: number; y: number; r: number; isShield?: boolean }[]>([]);
  const particlesRef = useRef<Particle[]>([]);
  const skylineOffsetRef = useRef<number>(0);
  const lastSpawnRef = useRef<number>(0);
  const animationFrameIdRef = useRef<number>(0);
  const groundY = 240;

  // Synthesized Web Audio SFX (Zero external asset dependencies)
  const playSfx = (type: 'jump' | 'coin' | 'shield' | 'crash') => {
    if (sfxMutedRef.current) return;
    try {
      if (!sfxContextRef.current || sfxContextRef.current.state === 'closed') {
        sfxContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)();
      }
      const ctx = sfxContextRef.current;
      if (ctx.state === 'suspended') ctx.resume();

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.connect(gain);
      gain.connect(ctx.destination);

      const now = ctx.currentTime;
      if (type === 'jump') {
        osc.type = 'sine';
        osc.frequency.setValueAtTime(320, now);
        osc.frequency.exponentialRampToValueAtTime(680, now + 0.11);
        gain.gain.setValueAtTime(0.12, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);
        osc.start(now);
        osc.stop(now + 0.12);
      } else if (type === 'coin') {
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(880, now);
        osc.frequency.setValueAtTime(1174, now + 0.05);
        gain.gain.setValueAtTime(0.14, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.14);
        osc.start(now);
        osc.stop(now + 0.14);
      } else if (type === 'shield') {
        osc.type = 'sine';
        osc.frequency.setValueAtTime(440, now);
        osc.frequency.exponentialRampToValueAtTime(880, now + 0.18);
        gain.gain.setValueAtTime(0.15, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.19);
        osc.start(now);
        osc.stop(now + 0.19);
      } else if (type === 'crash') {
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(190, now);
        osc.frequency.exponentialRampToValueAtTime(55, now + 0.25);
        gain.gain.setValueAtTime(0.18, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.26);
        osc.start(now);
        osc.stop(now + 0.26);
      }
    } catch {}
  };

  // Device Registration & X-Device-Token Enrollment
  const ensureDeviceEnrolled = async (): Promise<string | null> => {
    if (deviceTokenRef.current) return deviceTokenRef.current;
    try {
      const res = await fetch('/api/v1/devices/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: 'dev_pixel_7_studio',
          name: 'Scream Runner v3.0 Client',
          os_version: 'Android 14 / Web Audio Engine',
          app_version: '3.0.0',
        }),
      });
      if (res.ok) {
        const data = await res.json();
        if (data.device_token) {
          localStorage.setItem('sr_device_token', data.device_token);
          setDeviceToken(data.device_token);
          deviceTokenRef.current = data.device_token;
          return data.device_token;
        }
      }
    } catch {}
    return null;
  };

  // 1. Explicit User-Initiated Microphone Initialization
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

      const processor = audioCtx.createScriptProcessor(512, 1, 1);
      scriptProcessorRef.current = processor;

      source.connect(analyser);
      analyser.connect(processor);
      processor.connect(audioCtx.destination);

      let ambientFloor = -55;

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

        // Adaptive noise floor calibration during silence
        if (measuredDb < jumpThresholdDbRef.current - 6) {
          ambientFloor = 0.995 * ambientFloor + 0.005 * measuredDb;
          setNoiseFloorDb(Math.round(ambientFloor * 10) / 10);
        }

        // Voice-controlled Jump Trigger
        const activeGate = jumpThresholdDbRef.current;
        if (measuredDb > activeGate) {
          const intensity = Math.min(1.0, Math.max(0.25, (measuredDb - activeGate) / 20.0));
          jump(intensity);
        }

        // Offline VAD Processing when authorized recording is active
        if (isRecordingRef.current) {
          processVadFrame(new Float32Array(inputData), measuredDb, zcr / inputData.length);
        }
      };

      setMicActive(true);
      setMicPermissionDenied(false);
    } catch (err) {
      console.warn('Microphone permission not granted:', err);
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

  // Calibrate sensitivity automatically above measured room noise floor
  const calibrateNoiseFloor = () => {
    const calibrated = Math.min(-12, Math.max(-46, Math.round(noiseFloorDb + 15)));
    setJumpThresholdDb(calibrated);
  };

  // 2. Offline VAD logic with 300ms pre-roll & 500ms post-roll buffers
  const processVadFrame = (frame: Float32Array, measuredDb: number, zcrRate: number) => {
    const isSpeech = measuredDb > jumpThresholdDbRef.current && zcrRate > 0.015 && zcrRate < 0.7;

    if (isSpeech) {
      silentFramesCountRef.current = 0;
      if (!isSpeakingRef.current) {
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
      if (isSpeakingRef.current) {
        silentFramesCountRef.current++;
        speechSamplesRef.current.push(frame);
        setVadStatus('POST_HANGOVER');

        // ~500ms post-roll hangover (~45 frames)
        if (silentFramesCountRef.current >= 45) {
          isSpeakingRef.current = false;
          setVadStatus('IDLE');
          const speechEnd = Date.now();
          const allSamples = speechSamplesRef.current;
          speechSamplesRef.current = [];

          if (allSamples.length > 22) {
            uploadSpeechClip(allSamples, speechStartRef.current, speechEnd);
          }
        }
      } else {
        setVadStatus('IDLE');
        ringBufferRef.current.push(frame);
        if (ringBufferRef.current.length > 28) {
          ringBufferRef.current.shift();
        }
      }
    }
  };

  // 3. Real PCM WAV Encoding, SHA-256 Checksum & Authenticated Upload with Offline Retry Queue
  const uploadSpeechClip = async (chunks: Float32Array[], startTime: number, endTime: number) => {
    const totalLen = chunks.reduce((acc, c) => acc + c.length, 0);
    if (totalLen === 0) return;

    const merged = new Float32Array(totalLen);
    let offset = 0;
    for (const chunk of chunks) {
      merged.set(chunk, offset);
      offset += chunk.length;
    }

    const sampleRate = audioContextRef.current ? audioContextRef.current.sampleRate : 44100;
    const durationMs = Math.max(100, Math.round((totalLen / sampleRate) * 1000));
    const avgDb = speechDbCountRef.current > 0 ? speechDbSumRef.current / speechDbCountRef.current : -28;
    const peakDb = speechPeakDbRef.current;

    const barsCount = 50;
    const samplesPerBar = Math.max(1, Math.floor(totalLen / barsCount));
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

    const wavBlob = encodeWavBlob(merged, sampleRate);
    const arrayBuffer = await wavBlob.arrayBuffer();

    const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const fileHash = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');

    const seq = seqCounterRef.current++;
    const activeSessId = currentSessionIdRef.current || currentSessionId || `sess_${Date.now()}`;
    const formData = new FormData();
    formData.append('audio_file', wavBlob, `clip_${seq}.wav`);
    formData.append('session_id', activeSessId);
    formData.append('device_id', 'dev_pixel_7_studio');
    formData.append('device_name', 'Scream Runner v3.0 Client');
    formData.append('seq_index', String(seq));
    formData.append('start_time', String(startTime / 1000));
    formData.append('end_time', String(endTime / 1000));
    formData.append('duration_ms', String(durationMs));
    formData.append('peak_db', String(peakDb));
    formData.append('avg_db', String(avgDb));
    formData.append('speech_confidence', '0.96');
    formData.append('file_hash', fileHash);
    formData.append('waveform_json', JSON.stringify(waveformBars));

    await sendOrQueueUpload(formData);
  };

  const sendOrQueueUpload = async (formData: FormData) => {
    const token = await ensureDeviceEnrolled();
    const headers: HeadersInit = token ? { 'X-Device-Token': token } : {};
    try {
      const res = await fetch('/api/v1/audio/upload', {
        method: 'POST',
        headers,
        body: formData,
      });
      if (res.ok) {
        setUploadedSegmentsCount((c) => c + 1);
      } else {
        pendingUploadsRef.current.push(formData);
        setQueuedRetriesCount(pendingUploadsRef.current.length);
      }
    } catch {
      pendingUploadsRef.current.push(formData);
      setQueuedRetriesCount(pendingUploadsRef.current.length);
    }
  };

  // Retry queued uploads every 8 seconds
  useEffect(() => {
    const interval = setInterval(async () => {
      if (pendingUploadsRef.current.length === 0) return;
      const batch = [...pendingUploadsRef.current];
      pendingUploadsRef.current = [];
      setQueuedRetriesCount(0);
      for (const item of batch) {
        await sendOrQueueUpload(item);
      }
    }, 8000);
    return () => clearInterval(interval);
  }, []);

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
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
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

  // 4. Explicit Consent-Gated Recording Lifecycle
  const handleStartRecording = () => {
    setConsentModalOpen(true);
  };

  const handleConsentConfirmed = async () => {
    setConsentModalOpen(false);
    if (!micActive) {
      await startMic();
    }
    const token = await ensureDeviceEnrolled();
    const newSessionId = `sess_${Date.now()}`;
    currentSessionIdRef.current = newSessionId;
    isRecordingRef.current = true;
    setCurrentSessionId(newSessionId);
    setIsRecording(true);
    setRecordingSeconds(0);
    seqCounterRef.current = 1;

    try {
      const headers: HeadersInit = {
        'Content-Type': 'application/json',
        ...(token ? { 'X-Device-Token': token } : {}),
      };
      await fetch('/api/v1/sessions/start', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          session_id: newSessionId,
          device_id: 'dev_pixel_7_studio',
          device_name: 'Scream Runner v3.0 Client',
          consent_confirmed: true,
        }),
      });
    } catch (e) {
      console.warn('Backend session start notification failed:', e);
    }
  };

  const handleFlushLiveBuffer = async () => {
    const chunks = [...ringBufferRef.current, ...speechSamplesRef.current];
    if (chunks.length === 0) return;
    const now = Date.now();
    speechSamplesRef.current = [];
    ringBufferRef.current = [];
    await uploadSpeechClip(chunks, now - 1000, now);
  };

  const handleStopRecording = async () => {
    isRecordingRef.current = false;
    setIsRecording(false);
    setVadStatus('IDLE');
    if (speechSamplesRef.current.length > 8) {
      const now = Date.now();
      await uploadSpeechClip(speechSamplesRef.current, speechStartRef.current || now - 1000, now);
      speechSamplesRef.current = [];
    }
    const activeId = currentSessionIdRef.current || currentSessionId;
    if (activeId) {
      try {
        const token = deviceTokenRef.current;
        await fetch('/api/v1/sessions/stop', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(token ? { 'X-Device-Token': token } : {}),
          },
          body: JSON.stringify({ session_id: activeId }),
        });
      } catch (e) {
        console.warn('Backend session stop notification failed:', e);
      }
    }
  };

  useEffect(() => {
    let interval: any;
    if (isRecording) {
      interval = setInterval(() => {
        setRecordingSeconds((s) => s + 1);
      }, 1000);
    }
    return () => clearInterval(interval);
  }, [isRecording]);

  // Cleanup on unmount (Note: does NOT auto-start microphone on mount)
  useEffect(() => {
    return () => {
      stopMic();
      cancelAnimationFrame(animationFrameIdRef.current);
    };
  }, []);

  // 5. Delta-Time 60 FPS Game Loop with Frame-Time Monitoring & Multi-Obstacle Physics
  const spawnParticles = (x: number, y: number, color: string, count = 8) => {
    for (let i = 0; i < count; i++) {
      particlesRef.current.push({
        x,
        y,
        vx: (Math.random() - 0.5) * 180,
        vy: -Math.random() * 160 - 40,
        alpha: 1.0,
        color,
      });
    }
  };

  const jump = (intensity: number) => {
    if (gameState !== 'PLAYING') return;
    if (playerRef.current.onGround) {
      playerRef.current.vy = -420 - 180 * intensity;
      playerRef.current.onGround = false;
      playSfx('jump');
      spawnParticles(playerRef.current.x + 18, groundY, '#00F0FF', 6);
    }
  };

  const resetGame = () => {
    playerRef.current = {
      x: 80,
      y: groundY - 44,
      vy: 0,
      width: 36,
      height: 44,
      onGround: true,
      hasShield: false,
    };
    obstaclesRef.current = [];
    coinsRef.current = [];
    particlesRef.current = [];
    lastSpawnRef.current = performance.now();
    setScore(0);
    setCoins(0);
    setGameState('PLAYING');
  };

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let lastTime = performance.now();
    let frameCounter = 0;
    let fpsAccumulator = 0;

    const loop = (time: number) => {
      const rawDtMs = time - lastTime;
      lastTime = time;
      const dt = Math.min(0.05, Math.max(0.001, rawDtMs / 1000));

      frameCounter++;
      fpsAccumulator += rawDtMs;
      if (fpsAccumulator >= 500) {
        const currentFps = Math.min(60, Math.round((frameCounter * 1000) / fpsAccumulator));
        const avgMs = Math.round((fpsAccumulator / frameCounter) * 10) / 10;
        setFps(currentFps);
        setFrameTimeMs(avgMs);
        frameCounter = 0;
        fpsAccumulator = 0;
      }

      if (gameState === 'PLAYING') {
        const p = playerRef.current;
        p.vy += 1250 * dt; // Delta-time gravity (px/s^2)
        p.y += p.vy * dt;

        if (p.y >= groundY - p.height) {
          p.y = groundY - p.height;
          p.vy = 0;
          p.onGround = true;
        }

        const speedMultiplier = 1 + Math.min(1.2, score / 2500);
        const scrollSpeed = 240 * speedMultiplier;
        skylineOffsetRef.current = (skylineOffsetRef.current + scrollSpeed * 0.25 * dt) % 640;

        // Progressive obstacle spawner (Ground barriers, Aerial drones, Double barriers)
        const spawnInterval = Math.max(950, 1650 - score * 0.25);
        if (time - lastSpawnRef.current > spawnInterval) {
          const roll = Math.random();
          if (roll < 0.28) {
            // Aerial Drone
            obstaclesRef.current.push({
              x: canvas.width + 20,
              y: groundY - 88 - Math.random() * 25,
              w: 34,
              h: 24,
              type: 'drone',
            });
          } else if (roll < 0.55) {
            // Double Barrier
            obstaclesRef.current.push({
              x: canvas.width + 20,
              y: groundY - 42,
              w: 22,
              h: 42,
              type: 'double',
            });
            obstaclesRef.current.push({
              x: canvas.width + 52,
              y: groundY - 52,
              w: 22,
              h: 52,
              type: 'double',
            });
          } else {
            // Standard Ground Pillar
            const obsHeight = 36 + Math.random() * 38;
            obstaclesRef.current.push({
              x: canvas.width + 20,
              y: groundY - obsHeight,
              w: 28,
              h: obsHeight,
              type: 'ground',
            });
          }

          if (Math.random() > 0.25) {
            const isShield = Math.random() < 0.14 && !p.hasShield;
            coinsRef.current.push({
              x: canvas.width + 75,
              y: groundY - 68 - Math.random() * 55,
              r: isShield ? 12 : 9,
              isShield,
            });
          }
          lastSpawnRef.current = time;
        }

        // Update obstacles
        for (let i = obstaclesRef.current.length - 1; i >= 0; i--) {
          const obs = obstaclesRef.current[i];
          obs.x -= scrollSpeed * dt;

          // AABB Collision with 4px hitbox forgiveness
          if (
            p.x + 4 < obs.x + obs.w &&
            p.x + p.width - 4 > obs.x &&
            p.y + 4 < obs.y + obs.h &&
            p.y + p.height - 4 > obs.y
          ) {
            if (p.hasShield) {
              p.hasShield = false;
              obstaclesRef.current.splice(i, 1);
              spawnParticles(obs.x, obs.y, '#10B981', 12);
              playSfx('shield');
              continue;
            } else {
              playSfx('crash');
              spawnParticles(p.x + 18, p.y + 22, '#FF0055', 16);
              setGameState('GAMEOVER');
              setScore((cur) => {
                if (cur > highScore) {
                  localStorage.setItem('sr_high_score', String(cur));
                  setHighScore(cur);
                }
                return cur;
              });
            }
          }

          if (obs.x + obs.w < 0) {
            obstaclesRef.current.splice(i, 1);
            setScore((s) => {
              const next = s + 10;
              if (next > highScore) {
                localStorage.setItem('sr_high_score', String(next));
                setHighScore(next);
              }
              return next;
            });
          }
        }

        // Update coins & shield pickups
        for (let i = coinsRef.current.length - 1; i >= 0; i--) {
          const c = coinsRef.current[i];
          c.x -= scrollSpeed * dt;

          const dist = Math.hypot(p.x + p.width / 2 - c.x, p.y + p.height / 2 - c.y);
          if (dist < p.width / 2 + c.r) {
            coinsRef.current.splice(i, 1);
            if (c.isShield) {
              p.hasShield = true;
              playSfx('shield');
              spawnParticles(c.x, c.y, '#10B981', 10);
            } else {
              setCoins((cn) => cn + 1);
              setScore((s) => s + 50);
              playSfx('coin');
              spawnParticles(c.x, c.y, '#F59E0B', 8);
            }
          } else if (c.x + c.r < 0) {
            coinsRef.current.splice(i, 1);
          }
        }

        // Update particles
        for (let i = particlesRef.current.length - 1; i >= 0; i--) {
          const pt = particlesRef.current[i];
          pt.x += pt.vx * dt;
          pt.y += pt.vy * dt;
          pt.alpha -= 2.2 * dt;
          if (pt.alpha <= 0) {
            particlesRef.current.splice(i, 1);
          }
        }
      }

      // Render Canvas
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      ctx.fillStyle = '#080B11';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      // Synthwave perspective grid
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
      const buildings = [
        { x: 40, w: 50, h: 100 },
        { x: 110, w: 70, h: 140 },
        { x: 200, w: 45, h: 80 },
        { x: 260, w: 80, h: 160 },
        { x: 360, w: 60, h: 110 },
        { x: 440, w: 55, h: 130 },
        { x: 520, w: 70, h: 90 },
      ];
      for (const b of buildings) {
        const bx = ((b.x - skylineOffsetRef.current) % 640 + 640) % 640 - 60;
        ctx.fillRect(bx, groundY - b.h, b.w, b.h);
      }

      // Glowing Ground Line
      ctx.strokeStyle = '#00F0FF';
      ctx.lineWidth = 3;
      ctx.shadowColor = '#00F0FF';
      ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.moveTo(0, groundY);
      ctx.lineTo(canvas.width, groundY);
      ctx.stroke();
      ctx.shadowBlur = 0;

      // Obstacles
      for (const obs of obstaclesRef.current) {
        ctx.fillStyle = obs.type === 'drone' ? '#A855F7' : '#FF0055';
        ctx.shadowColor = obs.type === 'drone' ? '#A855F7' : '#FF0055';
        ctx.shadowBlur = 12;
        ctx.fillRect(obs.x, obs.y, obs.w, obs.h);
      }
      ctx.shadowBlur = 0;

      // Coins & Shields
      for (const c of coinsRef.current) {
        ctx.fillStyle = c.isShield ? '#10B981' : '#F59E0B';
        ctx.shadowColor = c.isShield ? '#10B981' : '#F59E0B';
        ctx.shadowBlur = 10;
        ctx.beginPath();
        ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.shadowBlur = 0;

      // Particles
      for (const pt of particlesRef.current) {
        ctx.fillStyle = pt.color;
        ctx.globalAlpha = Math.max(0, pt.alpha);
        ctx.fillRect(pt.x, pt.y, 4, 4);
      }
      ctx.globalAlpha = 1.0;

      // Player
      const p = playerRef.current;
      ctx.fillStyle = '#00F0FF';
      ctx.shadowColor = '#00F0FF';
      ctx.shadowBlur = 14;
      ctx.beginPath();
      ctx.roundRect(p.x, p.y, p.width, p.height, 8);
      ctx.fill();
      ctx.shadowBlur = 0;

      if (p.hasShield) {
        ctx.strokeStyle = '#10B981';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(p.x + p.width / 2, p.y + p.height / 2, 28, 0, Math.PI * 2);
        ctx.stroke();
      }

      // Visor
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(p.x + 18, p.y + 10, 14, 8);

      animationFrameIdRef.current = requestAnimationFrame(loop);
    };

    animationFrameIdRef.current = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(animationFrameIdRef.current);
  }, [gameState, score, highScore, groundY]);

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="flex flex-col h-full bg-[#0B0F19] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
      {/* Game Window Header with Real-Time FPS & Frame-Time Telemetry */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 bg-[#080B11] border-b border-slate-800 text-xs">
        <div className="flex items-center gap-2.5">
          <span className="font-bold text-cyan-400 tracking-wider">APK 1: SCREAM RUNNER v3.0</span>
          <span className="px-2 py-0.5 rounded bg-slate-900 border border-slate-800 text-[11px] text-emerald-400 font-mono">
            {fps} FPS · {frameTimeMs.toFixed(1)} ms
          </span>
        </div>
        <div className="flex items-center gap-4 font-mono">
          <span className="text-cyan-300 font-semibold">SCORE: {score}</span>
          <span className="text-slate-400">BEST: {highScore}</span>
          <span className="text-amber-400 font-semibold">COINS: {coins}</span>
        </div>
      </div>

      {/* Main Canvas Viewport */}
      <div className="relative w-full aspect-[16/9] bg-[#080B11] overflow-hidden select-none">
        <canvas
          ref={canvasRef}
          width={640}
          height={360}
          className="w-full h-full object-contain cursor-pointer"
          onClick={() => jump(0.85)}
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
              {micActive && currentDb > -90 ? `${currentDb.toFixed(1)} dB` : 'MIC OFF'}
            </span>
          </div>

          <div className="w-24 h-2 bg-slate-800 rounded-full overflow-hidden flex items-center">
            <div
              className={`h-full transition-all duration-75 ${
                currentDb > jumpThresholdDb ? 'bg-pink-500' : 'bg-cyan-400'
              }`}
              style={{
                width: `${micActive ? Math.min(100, Math.max(0, ((currentDb + 70) / 70) * 100)) : 0}%`,
              }}
            />
          </div>

          <span className="text-[10px] text-slate-400 font-mono">GATE: {jumpThresholdDb} dB</span>
        </div>

        {/* Title / Pause / Game Over Overlays */}
        {gameState === 'MENU' && (
          <div className="absolute inset-0 bg-black/80 backdrop-blur-sm flex flex-col items-center justify-center p-6 text-center">
            <h3 className="text-2xl font-bold text-cyan-400 tracking-wider mb-1">SCREAM RUNNER v3.0</h3>
            <p className="text-xs text-slate-300 max-w-sm mb-5">
              Tap the screen or enable voice-loudness jumping to leap over ground barriers and aerial drones.
            </p>
            <button
              onClick={resetGame}
              className="px-6 py-2.5 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-all flex items-center gap-2 shadow-lg shadow-cyan-500/20"
            >
              <Play className="w-4 h-4" />
              START RUN
            </button>
          </div>
        )}

        {gameState === 'PAUSED' && (
          <div className="absolute inset-0 bg-black/75 backdrop-blur-sm flex flex-col items-center justify-center p-6 text-center">
            <h3 className="text-xl font-bold text-white tracking-wider mb-3">GAME PAUSED</h3>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setGameState('PLAYING')}
                className="px-5 py-2 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg"
              >
                RESUME
              </button>
              <button
                onClick={resetGame}
                className="px-4 py-2 text-xs font-semibold text-slate-300 bg-slate-800 hover:bg-slate-700 rounded-lg"
              >
                RESTART
              </button>
            </div>
          </div>
        )}

        {gameState === 'GAMEOVER' && (
          <div className="absolute inset-0 bg-black/80 backdrop-blur-sm flex flex-col items-center justify-center p-6 text-center animate-in fade-in">
            <h3 className="text-2xl font-bold text-pink-500 tracking-wider mb-1">RUN TERMINATED</h3>
            <p className="text-xs text-slate-400 mb-4">Collision with Cyber Barrier</p>
            <div className="flex items-center gap-6 font-mono text-sm mb-6">
              <span className="text-slate-300">
                Score: <strong className="text-white">{score}</strong>
              </span>
              <span className="text-cyan-400">
                Best: <strong>{highScore}</strong>
              </span>
              <span className="text-amber-400">
                Coins: <strong>{coins}</strong>
              </span>
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
        <div className="flex flex-wrap items-center justify-between gap-2">
          <button
            onClick={() => jump(1.0)}
            className="flex-1 min-w-[130px] py-2 px-3 text-xs font-semibold text-cyan-200 bg-cyan-950/70 border border-cyan-800/60 rounded-lg hover:bg-cyan-900/60 transition-all flex items-center justify-center gap-1.5"
          >
            <Zap className="w-4 h-4 text-cyan-400" />
            TOUCH JUMP
          </button>

          <button
            onClick={() => setGameState((st) => (st === 'PLAYING' ? 'PAUSED' : 'PLAYING'))}
            className="py-2 px-3 text-xs font-medium text-slate-300 bg-slate-800/80 hover:bg-slate-700/80 rounded-lg transition-colors flex items-center gap-1.5"
          >
            {gameState === 'PLAYING' ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
            {gameState === 'PLAYING' ? 'Pause' : 'Resume'}
          </button>

          <button
            onClick={() => setSfxMuted((m) => !m)}
            className="py-2 px-3 text-xs font-medium text-slate-300 bg-slate-800/80 hover:bg-slate-700/80 rounded-lg transition-colors flex items-center gap-1.5"
            title="Toggle Synthesized SFX"
          >
            {sfxMuted ? <VolumeX className="w-3.5 h-3.5 text-rose-400" /> : <Volume2 className="w-3.5 h-3.5 text-cyan-400" />}
            {sfxMuted ? 'Muted' : 'SFX On'}
          </button>

          <button
            onClick={micActive ? stopMic : startMic}
            className={`py-2 px-3 text-xs font-medium rounded-lg transition-colors flex items-center gap-1.5 ${
              micActive
                ? 'bg-pink-950/60 text-pink-300 border border-pink-800/50 hover:bg-pink-900/60'
                : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
            }`}
          >
            <Mic className="w-3.5 h-3.5" />
            {micActive ? 'Voice Jump On' : 'Enable Voice Mic'}
          </button>
        </div>

        {/* Sensitivity Calibration Slider + Auto Noise-Floor Calibration */}
        <div className="flex flex-wrap items-center gap-3 bg-slate-900/50 p-2.5 rounded-lg border border-slate-800 text-xs">
          <span className="text-slate-400 shrink-0 font-medium">Jump Gate:</span>
          <input
            type="range"
            min="-50"
            max="-10"
            step="1"
            value={jumpThresholdDb}
            onChange={(e) => setJumpThresholdDb(Number(e.target.value))}
            className="flex-1 min-w-[100px] accent-cyan-400 cursor-pointer"
          />
          <span className="font-mono text-cyan-400 shrink-0 tabular-nums font-semibold">
            {jumpThresholdDb} dB
          </span>
          <button
            onClick={calibrateNoiseFloor}
            className="px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 font-mono text-[10px] flex items-center gap-1"
            title={`Ambient Floor: ${noiseFloorDb} dB`}
          >
            <Sliders className="w-3 h-3 text-cyan-400" />
            Auto-Calibrate ({noiseFloorDb} dB floor)
          </button>
        </div>

        {/* AUTHORIZED SMART AUDIO RECORDING SECTION */}
        <div className="pt-2 border-t border-slate-800/80 flex flex-col gap-2">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <Shield className="w-4 h-4 text-cyan-400" />
              <span className="font-semibold text-white">Explicitly Authorized Smart Recording</span>
            </div>
            {isRecording && (
              <span className="inline-flex items-center gap-1.5 text-xs font-mono text-red-400">
                <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                REC {formatSeconds(recordingSeconds)} (Foreground Active)
              </span>
            )}
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-950/60 p-3 rounded-xl border border-slate-800">
            <div>
              <div className="text-[11px] text-slate-300 mb-0.5">
                VAD Engine:{' '}
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
                {uploadedSegmentsCount} real clips synced
                {queuedRetriesCount > 0 ? ` · ${queuedRetriesCount} queued for retry` : ''}
              </div>
            </div>

            <div className="flex items-center gap-2">
              {isRecording && (
                <button
                  onClick={handleFlushLiveBuffer}
                  className="py-1.5 px-3 text-xs font-semibold text-amber-300 bg-amber-950/80 border border-amber-700/60 hover:bg-amber-900 rounded-lg transition-all flex items-center gap-1.5"
                  title="Flush live microphone PCM buffer to server"
                >
                  <Zap className="w-3.5 h-3.5" />
                  FLUSH BUFFER
                </button>
              )}
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
          {micPermissionDenied && (
            <div className="text-[11px] text-amber-400 font-mono">
              Microphone permission was declined in browser settings; touch controls remain active.
            </div>
          )}
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
