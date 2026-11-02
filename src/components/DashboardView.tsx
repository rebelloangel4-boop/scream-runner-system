import React, { useEffect, useState, useRef } from 'react';
import {
  ShieldCheck,
  Play,
  Pause,
  Download,
  Trash2,
  Search,
  Filter,
  RefreshCw,
  Clock,
  HardDrive,
  LogOut,
  AlertCircle,
  Activity,
  Layers,
  Sparkles
} from 'lucide-react';
import { Session, AudioSegment, Device } from '../types';
import { AudioWaveform } from './AudioWaveform';

export const DashboardView: React.FC = () => {
  // Auth state
  const [authToken, setAuthToken] = useState<string | null>(localStorage.getItem('admin_jwt'));
  const [username, setUsername] = useState<string>('admin');
  const [password, setPassword] = useState<string>('');
  const [showLoginModal, setShowLoginModal] = useState<boolean>(false);
  const [loginError, setLoginError] = useState<string | null>(null);

  // Data state
  const [devices, setDevices] = useState<Device[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [segments, setSegments] = useState<AudioSegment[]>([]);
  const [selectedSegment, setSelectedSegment] = useState<AudioSegment | null>(null);
  const [storageStats, setStorageStats] = useState<any>(null);

  // Filter & Search state
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [loudnessFilterDb, setLoudnessFilterDb] = useState<number>(-35);

  // Audio Playback state
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1.0);
  const [playbackProgress, setPlaybackProgress] = useState<number>(0);
  const [currentTimeMs, setCurrentTimeMs] = useState<number>(0);
  const [durationMs, setDurationMs] = useState<number>(0);

  // Audio element ref
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // WebSocket ref
  const wsRef = useRef<WebSocket | null>(null);
  const [wsConnected, setWsConnected] = useState<boolean>(false);
  const lastEventSeqRef = useRef<number>(0);

  // 1. Login & Auth
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginError(null);
    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (res.ok) {
        const data = await res.json();
        setAuthToken(data.access_token);
        localStorage.setItem('admin_jwt', data.access_token);
        fetchInitialData();
      } else {
        const err = await res.json();
        setLoginError(err.detail || 'Login failed');
      }
    } catch (e: any) {
      setLoginError('Could not reach backend server');
    }
  };

  const handleLogout = async () => {
    if (authToken) {
      try {
        await fetch('/api/v1/auth/logout', {
          method: 'POST',
          headers: { Authorization: `Bearer ${authToken}` },
        });
      } catch (ignored) {}
    }
    setAuthToken(null);
    localStorage.removeItem('admin_jwt');
  };

  // 2. Fetch Data
  const fetchInitialData = async () => {
    try {
      // Fetch devices
      const devRes = await fetch('/api/v1/devices');
      if (devRes.ok) {
        const d = await devRes.json();
        setDevices(d.devices || []);
      }

      // Fetch sessions
      const sessRes = await fetch('/api/v1/sessions');
      if (sessRes.ok) {
        const s = await sessRes.json();
        setSessions(s.sessions || []);
        if (s.sessions && s.sessions.length > 0 && !selectedSessionId) {
          setSelectedSessionId(s.sessions[0].id);
        }
      }

      // Fetch storage stats
      const stRes = await fetch('/api/v1/storage/stats');
      if (stRes.ok) {
        setStorageStats(await stRes.json());
      }
    } catch (e) {
      console.warn('Error loading dashboard data:', e);
    }
  };

  // Fetch segments when session selected
  useEffect(() => {
    if (!selectedSessionId) return;
    const fetchSegments = async () => {
      try {
        const res = await fetch(`/api/v1/sessions/${selectedSessionId}/segments`);
        if (res.ok) {
          const data = await res.json();
          setSegments(data.segments || []);
          if (data.segments && data.segments.length > 0) {
            setSelectedSegment(data.segments[0]);
          }
        }
      } catch (e) {
        console.warn('Error fetching segments:', e);
      }
    };
    fetchSegments();
  }, [selectedSessionId]);

  // Initial load
  useEffect(() => {
    fetchInitialData();
  }, []);

  // 3. WebSocket Auto-Reconnect & Missed Event Catchup
  useEffect(() => {
    let ws: WebSocket;
    let reconnectTimeout: any;

    const connectWs = () => {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/ws/live-feed`;
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        setWsConnected(true);
        // Query missed events since last received sequence
        if (lastEventSeqRef.current > 0) {
          fetch(`/api/v1/events/missed?since_seq=${lastEventSeqRef.current}`)
            .then((r) => r.json())
            .then((data) => {
              if (data.missed_events && data.missed_events.length > 0) {
                fetchInitialData();
              }
              lastEventSeqRef.current = data.latest_seq || lastEventSeqRef.current;
            })
            .catch(() => {});
        }
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.seq) {
            lastEventSeqRef.current = Math.max(lastEventSeqRef.current, msg.seq);
          }
          if (msg.type === 'AUDIO_UPLOADED' || msg.type === 'SESSION_START' || msg.type === 'SESSION_STOP') {
            fetchInitialData();
            if (selectedSessionId && msg.session_id === selectedSessionId) {
              fetch(`/api/v1/sessions/${selectedSessionId}/segments`)
                .then((r) => r.json())
                .then((data) => setSegments(data.segments || []));
            }
          }
        } catch (e) {}
      };

      ws.onclose = () => {
        setWsConnected(false);
        reconnectTimeout = setTimeout(connectWs, 3000);
      };

      ws.onerror = () => {
        ws.close();
      };

      wsRef.current = ws;
    };

    connectWs();

    return () => {
      clearTimeout(reconnectTimeout);
      if (wsRef.current) wsRef.current.close();
    };
  }, [selectedSessionId]);

  // 4. Audio Playback via HTML5 Audio with HTTP 206 Partial Content
  const handlePlaySegment = (seg: AudioSegment) => {
    setSelectedSegment(seg);
    const audio = audioRef.current;
    if (!audio) return;

    audio.src = `/api/v1/audio/${seg.id}/stream`;
    audio.playbackRate = playbackSpeed;
    audio.play().then(() => setIsPlaying(true)).catch((e) => console.warn('Play error:', e));
  };

  const togglePlayPause = () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (isPlaying) {
      audio.pause();
      setIsPlaying(false);
    } else {
      if (!audio.src && selectedSegment) {
        audio.src = `/api/v1/audio/${selectedSegment.id}/stream`;
      }
      audio.playbackRate = playbackSpeed;
      audio.play().then(() => setIsPlaying(true)).catch((e) => console.warn('Play error:', e));
    }
  };

  const handleScrub = (ratio: number) => {
    const audio = audioRef.current;
    if (!audio || !audio.duration) return;
    audio.currentTime = audio.duration * ratio;
    setPlaybackProgress(ratio);
  };

  const changeSpeed = (speed: number) => {
    setPlaybackSpeed(speed);
    if (audioRef.current) {
      audioRef.current.playbackRate = speed;
    }
  };

  const handleDeleteSegment = async (segId: string) => {
    try {
      const res = await fetch(`/api/v1/audio/${segId}`, { method: 'DELETE' });
      if (res.ok) {
        setSegments((prev) => prev.filter((s) => s.id !== segId));
        if (selectedSegment?.id === segId) {
          setSelectedSegment(null);
        }
        fetchInitialData();
      }
    } catch (e) {
      console.warn('Error deleting segment:', e);
    }
  };

  const parseWaveform = (jsonStr?: string): number[] => {
    if (!jsonStr) return [];
    try {
      return JSON.parse(jsonStr);
    } catch {
      return [];
    }
  };

  // Filtered segments
  const filteredSegments = segments.filter((s) => {
    const matchesLoudness = s.peak_db >= loudnessFilterDb;
    const matchesSearch = searchQuery === '' || s.id.includes(searchQuery) || String(s.seq_index).includes(searchQuery);
    return matchesLoudness && matchesSearch;
  });

  return (
    <div className="flex flex-col h-full bg-[#080B11] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
      {/* Hidden Audio Element with event listeners */}
      <audio
        ref={audioRef}
        onTimeUpdate={() => {
          if (audioRef.current) {
            const cur = audioRef.current.currentTime;
            const dur = audioRef.current.duration || 1;
            setCurrentTimeMs(Math.round(cur * 1000));
            setDurationMs(Math.round(dur * 1000));
            setPlaybackProgress(cur / dur);
          }
        }}
        onEnded={() => {
          setIsPlaying(false);
          setPlaybackProgress(0);
        }}
      />

      {/* Dashboard Top Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 px-6 py-3.5 bg-[#0B0F19] border-b border-slate-800">
        <div className="flex items-center gap-3">
          <ShieldCheck className="w-5 h-5 text-cyan-400" />
          <div>
            <h2 className="text-sm font-bold text-white tracking-tight">APK 2: SAM'S DASHBOARD</h2>
            <div className="flex items-center gap-2 text-xs text-slate-400 font-mono">
              <span>ADMINISTRATOR CONSOLE</span>
              <span>·</span>
              <span className={`inline-flex items-center gap-1 ${wsConnected ? 'text-emerald-400' : 'text-amber-400'}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse'}`} />
                {wsConnected ? 'SYNC LIVE' : 'CONNECTING'}
              </span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3 text-xs">
          {storageStats && (
            <div className="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded bg-slate-900 border border-slate-800 font-mono text-slate-300">
              <HardDrive className="w-3.5 h-3.5 text-cyan-400" />
              <span>{storageStats.total_megabytes} MB</span>
              <span className="text-slate-500">({storageStats.total_segments} clips)</span>
            </div>
          )}

          {authToken ? (
            <button
              onClick={handleLogout}
              className="py-1 px-2.5 text-xs text-slate-300 hover:text-white bg-slate-800 hover:bg-slate-700 rounded transition-colors flex items-center gap-1.5"
            >
              <LogOut className="w-3.5 h-3.5" />
              Logout
            </button>
          ) : (
            <button
              onClick={() => setShowLoginModal(true)}
              className="py-1 px-2.5 text-xs text-cyan-300 hover:text-white bg-cyan-950/70 border border-cyan-800/60 hover:bg-cyan-900 rounded transition-colors flex items-center gap-1.5"
            >
              Admin Login
            </button>
          )}
        </div>
      </div>

      {/* Admin Login Modal */}
      {showLoginModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="bg-[#0F172A] border border-cyan-500/40 rounded-xl max-w-sm w-full p-5 shadow-2xl">
            <h3 className="text-sm font-bold text-white mb-1">Administrator Authentication</h3>
            <p className="text-xs text-slate-400 mb-4 font-mono">JWT Bearer Token with Revocation</p>
            {loginError && (
              <div className="p-2 mb-3 rounded bg-red-950/80 border border-red-800/60 text-xs text-red-300">
                {loginError}
              </div>
            )}
            <form onSubmit={handleLogin} className="space-y-3">
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">Username</label>
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded p-2 text-xs text-white"
                  required
                />
              </div>
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">Password</label>
                <input
                  type="password"
                  placeholder="Enter administrator password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded p-2 text-xs text-white"
                  required
                />
              </div>
              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowLoginModal(false)}
                  className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-1.5 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded"
                >
                  Sign In
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Main Dashboard Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-12 flex-1 overflow-hidden">
        {/* Left Column: Sessions List (4 cols) */}
        <div className="lg:col-span-4 border-r border-slate-800 bg-[#0A0E17] flex flex-col overflow-hidden">
          <div className="p-3 border-b border-slate-800 flex items-center justify-between text-xs">
            <span className="font-semibold text-slate-300 flex items-center gap-1.5">
              <Layers className="w-4 h-4 text-cyan-400" />
              Recording Sessions
            </span>
            <button
              onClick={fetchInitialData}
              className="text-slate-400 hover:text-white transition-colors"
              title="Refresh"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto divide-y divide-slate-800/60 p-2 space-y-1.5">
            {sessions.map((sess) => (
              <div
                key={sess.id}
                onClick={() => setSelectedSessionId(sess.id)}
                className={`p-3 rounded-xl cursor-pointer transition-all border ${
                  selectedSessionId === sess.id
                    ? 'bg-cyan-950/40 border-cyan-500/40 text-white'
                    : 'bg-slate-900/40 border-slate-800/80 hover:bg-slate-900 text-slate-300'
                }`}
              >
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="font-medium text-cyan-300">
                    {sess.device_name || 'Pixel 7 Pro'}
                  </span>
                  <span
                    className={`px-1.5 py-0.5 rounded text-[10px] font-mono ${
                      sess.status === 'active'
                        ? 'bg-red-950/80 text-red-400 border border-red-800/50'
                        : 'bg-slate-800 text-slate-400'
                    }`}
                  >
                    {sess.status.toUpperCase()}
                  </span>
                </div>

                <div className="text-[11px] text-slate-400 font-mono">
                  {new Date(sess.started_at * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} · {sess.total_speech_segments} speech bursts
                </div>

                {sess.interruption_count ? (
                  <div className="text-[10px] text-amber-400 mt-1 font-mono flex items-center gap-1">
                    <AlertCircle className="w-3 h-3" />
                    {sess.interruption_count} gap intervals recorded
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        </div>

        {/* Right Column: Audio Inspector & Clips Grid (8 cols) */}
        <div className="lg:col-span-8 flex flex-col bg-[#0B0F19] overflow-hidden">
          {/* Active Player Deck */}
          <div className="p-5 border-b border-slate-800 bg-[#0E1420]/80 backdrop-blur-md">
            <div className="flex items-center justify-between text-xs text-slate-400 mb-2 font-mono">
              <span className="flex items-center gap-1.5 text-cyan-400 font-semibold">
                <Activity className="w-4 h-4" />
                PCM AUDIO INSPECTION & WAVEFORM
              </span>
              <span>
                {selectedSegment ? (
                  <>
                    Seq #{selectedSegment.seq_index} · {selectedSegment.duration_ms} ms ·{' '}
                    <span className={selectedSegment.is_highlight ? 'text-pink-400 font-bold' : 'text-slate-300'}>
                      {selectedSegment.peak_db.toFixed(1)} dB
                    </span>
                  </>
                ) : (
                  'Select a clip below'
                )}
              </span>
            </div>

            {/* Waveform Visualizer */}
            <AudioWaveform
              amplitudes={parseWaveform(selectedSegment?.waveform_data)}
              progress={playbackProgress}
              onScrub={handleScrub}
              peakDb={selectedSegment?.peak_db || -96}
              isHighlight={selectedSegment?.is_highlight}
            />

            {/* Playback Controls & Speed Multipliers */}
            <div className="flex flex-wrap items-center justify-between gap-3 mt-4 text-xs">
              <div className="flex items-center gap-2">
                <button
                  onClick={togglePlayPause}
                  className="px-4 py-2 font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-all flex items-center gap-2 shadow-md shadow-cyan-500/20"
                >
                  {isPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                  {isPlaying ? 'PAUSE' : 'PLAY CLIP'}
                </button>

                <div className="text-slate-400 font-mono text-xs px-2">
                  {(currentTimeMs / 1000).toFixed(1)}s / {(durationMs / 1000).toFixed(1)}s
                </div>
              </div>

              {/* Speed controls */}
              <div className="flex items-center gap-1 bg-slate-900 p-1 rounded-lg border border-slate-800 text-[11px] font-mono">
                {[0.75, 1.0, 1.25, 1.5, 2.0].map((s) => (
                  <button
                    key={s}
                    onClick={() => changeSpeed(s)}
                    className={`px-2 py-1 rounded transition-colors ${
                      playbackSpeed === s
                        ? 'bg-cyan-500 text-[#080B11] font-bold'
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    {s}x
                  </button>
                ))}
              </div>

              {/* Download Audio */}
              {selectedSegment && (
                <a
                  href={`/api/v1/audio/${selectedSegment.id}/download`}
                  download
                  className="px-3 py-1.5 text-xs font-semibold text-emerald-400 bg-emerald-950/70 border border-emerald-800/60 rounded-lg hover:bg-emerald-900/60 transition-colors flex items-center gap-1.5"
                >
                  <Download className="w-3.5 h-3.5" />
                  Download Clip
                </a>
              )}
            </div>
          </div>

          {/* Search & Loudness Hotspot Filter Bar */}
          <div className="p-3 border-b border-slate-800 bg-[#080B11] flex flex-wrap items-center justify-between gap-3 text-xs">
            <div className="flex items-center gap-2 flex-1 max-w-xs bg-slate-900/80 px-2.5 py-1.5 rounded-lg border border-slate-800">
              <Search className="w-3.5 h-3.5 text-slate-500" />
              <input
                type="text"
                placeholder="Search speech clips..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="bg-transparent border-none outline-none text-white text-xs w-full placeholder:text-slate-600"
              />
            </div>

            <div className="flex items-center gap-3">
              <span className="text-slate-400 text-xs">Min Loudness Gate:</span>
              <input
                type="range"
                min="-40"
                max="-5"
                step="1"
                value={loudnessFilterDb}
                onChange={(e) => setLoudnessFilterDb(Number(e.target.value))}
                className="w-24 accent-pink-500 cursor-pointer"
              />
              <span className="font-mono text-pink-400 font-semibold">{loudnessFilterDb} dB</span>
            </div>
          </div>

          {/* Speech Clips Grid */}
          <div className="flex-1 overflow-y-auto p-4 space-y-2">
            {filteredSegments.length === 0 ? (
              <div className="p-8 text-center text-slate-500 text-xs font-mono">
                No speech clips match the current loudness filter.
              </div>
            ) : (
              filteredSegments.map((seg) => (
                <div
                  key={seg.id}
                  onClick={() => handlePlaySegment(seg)}
                  className={`p-3 rounded-xl border flex items-center justify-between gap-4 cursor-pointer transition-all ${
                    selectedSegment?.id === seg.id
                      ? 'bg-cyan-950/30 border-cyan-500/50 text-white'
                      : 'bg-slate-900/40 border-slate-800/80 hover:bg-slate-900 text-slate-300'
                  }`}
                >
                  <div className="flex items-center gap-3">
                    <div
                      className={`w-8 h-8 rounded-lg flex items-center justify-center font-mono text-xs font-bold ${
                        seg.is_highlight
                          ? 'bg-pink-950/80 text-pink-400 border border-pink-700/60'
                          : 'bg-slate-800 text-cyan-400'
                      }`}
                    >
                      #{seg.seq_index}
                    </div>

                    <div>
                      <div className="text-xs font-semibold text-white flex items-center gap-2">
                        <span>Speech Burst</span>
                        {seg.is_highlight && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-pink-950 text-pink-400 border border-pink-800/50 flex items-center gap-1">
                            <Sparkles className="w-2.5 h-2.5" />
                            PEAK {seg.peak_db.toFixed(1)} dB
                          </span>
                        )}
                      </div>
                      <div className="text-[11px] text-slate-400 font-mono">
                        {seg.duration_ms} ms · Avg: {seg.avg_db.toFixed(1)} dB · Confidence: 96%
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handlePlaySegment(seg);
                      }}
                      className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-300 transition-colors"
                      title="Play Clip"
                    >
                      <Play className="w-3.5 h-3.5" />
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDeleteSegment(seg.id);
                      }}
                      className="p-2 rounded-lg bg-slate-800/60 hover:bg-red-950 text-slate-500 hover:text-red-400 transition-colors"
                      title="Delete from Storage"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
