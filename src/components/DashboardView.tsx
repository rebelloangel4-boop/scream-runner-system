import React, { useEffect, useState, useRef } from 'react';
import {
  ShieldCheck,
  Play,
  Pause,
  Download,
  Trash2,
  Search,
  RefreshCw,
  HardDrive,
  LogOut,
  AlertCircle,
  Activity,
  Layers,
  Sparkles,
  KeyRound,
  Lock,
  DatabaseBackup,
  Upload,
  FileText,
  Copy,
  Check
} from 'lucide-react';
import { Session, AudioSegment, Device } from '../types';
import { AudioWaveform } from './AudioWaveform';

export const DashboardView: React.FC = () => {
  // Auth state (Zero hardcoded credentials)
  const [authToken, setAuthToken] = useState<string | null>(localStorage.getItem('admin_jwt'));
  const [refreshToken, setRefreshToken] = useState<string | null>(localStorage.getItem('admin_refresh_jwt'));
  const [setupRequired, setSetupRequired] = useState<boolean>(false);
  const [authMode, setAuthMode] = useState<'login' | 'setup' | 'reset'>('login');
  const [username, setUsername] = useState<string>('admin');
  const [password, setPassword] = useState<string>('');
  const [confirmPassword, setConfirmPassword] = useState<string>('');
  const [recoveryKeyInput, setRecoveryKeyInput] = useState<string>('');
  const [generatedRecoveryKey, setGeneratedRecoveryKey] = useState<string | null>(null);
  const [copiedRecovery, setCopiedRecovery] = useState<boolean>(false);
  const [showAuthModal, setShowAuthModal] = useState<boolean>(false);
  const [authError, setAuthError] = useState<string | null>(null);
  const [authMessage, setAuthMessage] = useState<string | null>(null);

  // Data state
  const [devices, setDevices] = useState<Device[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [segments, setSegments] = useState<AudioSegment[]>([]);
  const [selectedSegment, setSelectedSegment] = useState<AudioSegment | null>(null);
  const [storageStats, setStorageStats] = useState<any>(null);
  const [auditLogs, setAuditLogs] = useState<any[]>([]);
  const [showAuditModal, setShowAuditModal] = useState<boolean>(false);
  const [backupStatusMsg, setBackupStatusMsg] = useState<string | null>(null);

  // Filter & Search state
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [loudnessFilterDb, setLoudnessFilterDb] = useState<number>(-40);
  const [peaksOnlyFilter, setPeaksOnlyFilter] = useState<boolean>(false);

  // Audio Playback state
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1.0);
  const [playbackProgress, setPlaybackProgress] = useState<number>(0);
  const [currentTimeMs, setCurrentTimeMs] = useState<number>(0);
  const [durationMs, setDurationMs] = useState<number>(0);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const restoreInputRef = useRef<HTMLInputElement | null>(null);

  // WebSocket ref
  const wsRef = useRef<WebSocket | null>(null);
  const [wsConnected, setWsConnected] = useState<boolean>(false);
  const lastEventSeqRef = useRef<number>(0);

  // Check first-time setup status on mount
  const checkAuthStatus = async () => {
    try {
      const res = await fetch('/api/v1/auth/status');
      if (res.ok) {
        const data = await res.json();
        setSetupRequired(Boolean(data.setup_required));
        if (data.setup_required) {
          setAuthMode('setup');
        }
      }
    } catch (e) {
      console.warn('Auth status check failed:', e);
    }
  };

  useEffect(() => {
    checkAuthStatus();
  }, []);

  // Helper for authenticated requests with automatic refresh-token rotation
  const fetchWithAuth = async (url: string, options: RequestInit = {}): Promise<Response> => {
    let token = localStorage.getItem('admin_jwt') || authToken;
    const headers = new Headers(options.headers || {});
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    let res = await fetch(url, { ...options, headers });
    if (res.status === 401) {
      const storedRefresh = localStorage.getItem('admin_refresh_jwt') || refreshToken;
      if (storedRefresh) {
        const refRes = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: storedRefresh }),
        });
        if (refRes.ok) {
          const refData = await refRes.json();
          localStorage.setItem('admin_jwt', refData.access_token);
          setAuthToken(refData.access_token);
          if (refData.refresh_token) {
            localStorage.setItem('admin_refresh_jwt', refData.refresh_token);
            setRefreshToken(refData.refresh_token);
          }
          headers.set('Authorization', `Bearer ${refData.access_token}`);
          res = await fetch(url, { ...options, headers });
          return res;
        }
      }
      // Clear invalid session
      localStorage.removeItem('admin_jwt');
      localStorage.removeItem('admin_refresh_jwt');
      setAuthToken(null);
      setRefreshToken(null);
    }
    return res;
  };

  // 1. Setup / Login / Reset Handlers
  const handleAuthSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError(null);
    setAuthMessage(null);

    try {
      if (authMode === 'setup') {
        if (password.length < 10) {
          setAuthError('Password must be at least 10 characters.');
          return;
        }
        if (password !== confirmPassword) {
          setAuthError('Passwords do not match.');
          return;
        }
        const res = await fetch('/api/v1/auth/setup', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: username.trim(), password }),
        });
        const data = await res.json();
        if (res.ok) {
          localStorage.setItem('admin_jwt', data.access_token);
          localStorage.setItem('admin_refresh_jwt', data.refresh_token || '');
          setAuthToken(data.access_token);
          setRefreshToken(data.refresh_token || null);
          setSetupRequired(false);
          setGeneratedRecoveryKey(data.recovery_key || null);
          setPassword('');
          setConfirmPassword('');
          fetchInitialData(data.access_token);
        } else {
          setAuthError(data.detail || 'Setup failed');
        }
      } else if (authMode === 'login') {
        const res = await fetch('/api/v1/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: username.trim(), password }),
        });
        const data = await res.json();
        if (res.ok) {
          localStorage.setItem('admin_jwt', data.access_token);
          localStorage.setItem('admin_refresh_jwt', data.refresh_token || '');
          setAuthToken(data.access_token);
          setRefreshToken(data.refresh_token || null);
          setPassword('');
          setShowAuthModal(false);
          fetchInitialData(data.access_token);
        } else {
          setAuthError(data.detail || 'Invalid username or password');
        }
      } else if (authMode === 'reset') {
        if (password.length < 10) {
          setAuthError('New password must be at least 10 characters.');
          return;
        }
        const res = await fetch('/api/v1/auth/reset-password', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: username.trim(),
            recovery_key: recoveryKeyInput.trim(),
            new_password: password,
          }),
        });
        const data = await res.json();
        if (res.ok) {
          setAuthMessage('Password reset verified! Please sign in with your new password.');
          setAuthMode('login');
          setPassword('');
          setRecoveryKeyInput('');
        } else {
          setAuthError(data.detail || 'Password reset failed');
        }
      }
    } catch (err: any) {
      setAuthError('Could not reach backend server');
    }
  };

  const handleLogout = async () => {
    try {
      await fetchWithAuth('/api/v1/auth/logout', { method: 'POST' });
    } catch {}
    setAuthToken(null);
    setRefreshToken(null);
    setDevices([]);
    setSessions([]);
    setSegments([]);
    setSelectedSegment(null);
    localStorage.removeItem('admin_jwt');
    localStorage.removeItem('admin_refresh_jwt');
  };

  // 2. Fetch Data (Strictly Authenticated)
  const fetchInitialData = async (overrideToken?: string) => {
    const activeToken = overrideToken || localStorage.getItem('admin_jwt') || authToken;
    try {
      // Storage stats is public summary, fetch it anytime
      const stRes = await fetch('/api/v1/storage/stats');
      if (stRes.ok) {
        setStorageStats(await stRes.json());
      }

      if (!activeToken) return;

      const devRes = await fetchWithAuth('/api/v1/devices');
      if (devRes.ok) {
        const d = await devRes.json();
        setDevices(d.devices || []);
      }

      const sessRes = await fetchWithAuth('/api/v1/sessions');
      if (sessRes.ok) {
        const s = await sessRes.json();
        const sessList: Session[] = s.sessions || [];
        setSessions(sessList);
        if (sessList.length > 0 && (!selectedSessionId || !sessList.some((x) => x.id === selectedSessionId))) {
          setSelectedSessionId(sessList[0].id);
        } else if (sessList.length === 0) {
          setSelectedSessionId(null);
          setSegments([]);
          setSelectedSegment(null);
        }
      }
    } catch (e) {
      console.warn('Error loading dashboard data:', e);
    }
  };

  // Fetch segments when session selected
  useEffect(() => {
    if (!selectedSessionId || !authToken) return;
    const fetchSegments = async () => {
      try {
        const res = await fetchWithAuth(`/api/v1/sessions/${selectedSessionId}/segments`);
        if (res.ok) {
          const data = await res.json();
          const segList: AudioSegment[] = data.segments || [];
          setSegments(segList);
          if (segList.length > 0) {
            setSelectedSegment(segList[0]);
          } else {
            setSelectedSegment(null);
          }
        }
      } catch (e) {
        console.warn('Error fetching segments:', e);
      }
    };
    fetchSegments();
  }, [selectedSessionId, authToken]);

  useEffect(() => {
    fetchInitialData();
  }, [authToken]);

  // 3. Authenticated WebSocket Auto-Reconnect & Missed Event Catchup
  useEffect(() => {
    if (!authToken) {
      setWsConnected(false);
      return;
    }

    let ws: WebSocket;
    let reconnectTimeout: any;

    const connectWs = () => {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/ws/live-feed?token=${encodeURIComponent(authToken)}`;
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        setWsConnected(true);
        if (lastEventSeqRef.current > 0) {
          fetchWithAuth(`/api/v1/events/missed?since_seq=${lastEventSeqRef.current}`)
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => {
              if (data && data.missed_events && data.missed_events.length > 0) {
                fetchInitialData();
              }
              if (data && data.latest_seq) {
                lastEventSeqRef.current = data.latest_seq;
              }
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
          if (
            msg.type === 'AUDIO_UPLOADED' ||
            msg.type === 'SESSION_START' ||
            msg.type === 'SESSION_STOP' ||
            msg.type === 'AUDIO_DELETED' ||
            msg.type === 'SESSION_INTERRUPTED'
          ) {
            fetchInitialData();
            if (selectedSessionId && (!msg.session_id || msg.session_id === selectedSessionId)) {
              fetchWithAuth(`/api/v1/sessions/${selectedSessionId}/segments`)
                .then((r) => (r.ok ? r.json() : null))
                .then((data) => {
                  if (data) setSegments(data.segments || []);
                });
            }
          }
        } catch {}
      };

      ws.onclose = () => {
        setWsConnected(false);
        reconnectTimeout = setTimeout(connectWs, 3500);
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
  }, [authToken, selectedSessionId]);

  // 4. Audio Playback via HTML5 Audio with Authenticated Token & HTTP 206 Partial Content
  const getAuthenticatedStreamUrl = (segId: string) => {
    const token = localStorage.getItem('admin_jwt') || authToken || '';
    return `/api/v1/audio/${segId}/stream?token=${encodeURIComponent(token)}`;
  };

  const getAuthenticatedDownloadUrl = (segId: string) => {
    const token = localStorage.getItem('admin_jwt') || authToken || '';
    return `/api/v1/audio/${segId}/download?token=${encodeURIComponent(token)}`;
  };

  const handlePlaySegment = (seg: AudioSegment) => {
    setSelectedSegment(seg);
    const audio = audioRef.current;
    if (!audio) return;

    audio.src = getAuthenticatedStreamUrl(seg.id);
    audio.playbackRate = playbackSpeed;
    audio.play().then(() => setIsPlaying(true)).catch((e) => console.warn('Play error:', e));
  };

  const togglePlayPause = () => {
    const audio = audioRef.current;
    if (!audio || !selectedSegment) return;
    if (isPlaying) {
      audio.pause();
      setIsPlaying(false);
    } else {
      if (!audio.src || !audio.src.includes(selectedSegment.id)) {
        audio.src = getAuthenticatedStreamUrl(selectedSegment.id);
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
      const res = await fetchWithAuth(`/api/v1/audio/${segId}`, {
        method: 'DELETE',
      });
      if (res.ok) {
        setSegments((prev) => prev.filter((s) => s.id !== segId));
        if (selectedSegment?.id === segId) {
          setSelectedSegment(null);
          setIsPlaying(false);
        }
        fetchInitialData();
      }
    } catch (e) {
      console.warn('Error deleting segment:', e);
    }
  };

  // 5. Portable Backup Export & Restore + Audit Logs
  const handleExportBackup = async () => {
    try {
      setBackupStatusMsg('Preparing backup...');
      const res = await fetchWithAuth('/api/v1/storage/backup');
      if (res.ok) {
        const data = await res.json();
        const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `scream-runner-backup-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
        setBackupStatusMsg('Backup exported');
        setTimeout(() => setBackupStatusMsg(null), 3000);
      } else {
        setBackupStatusMsg('Backup failed');
      }
    } catch {
      setBackupStatusMsg('Backup failed');
    }
  };

  const handleRestoreFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      setBackupStatusMsg('Restoring snapshot...');
      const text = await file.text();
      const json = JSON.parse(text);
      const res = await fetchWithAuth('/api/v1/storage/restore', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(json),
      });
      if (res.ok) {
        const resData = await res.json();
        setBackupStatusMsg(`Restored ${resData.restored_segments} clips`);
        fetchInitialData();
        setTimeout(() => setBackupStatusMsg(null), 4000);
      } else {
        setBackupStatusMsg('Restore failed');
      }
    } catch {
      setBackupStatusMsg('Invalid backup JSON');
    } finally {
      if (restoreInputRef.current) restoreInputRef.current.value = '';
    }
  };

  const handleOpenAuditLogs = async () => {
    setShowAuditModal(true);
    try {
      const res = await fetchWithAuth('/api/v1/audit/logs');
      if (res.ok) {
        const data = await res.json();
        setAuditLogs(data.audit_logs || []);
      }
    } catch {}
  };

  const parseWaveform = (jsonStr?: string): number[] => {
    if (!jsonStr) return [];
    try {
      return JSON.parse(jsonStr);
    } catch {
      return [];
    }
  };

  const filteredSegments = segments.filter((s) => {
    const effectiveGate = peaksOnlyFilter ? -10 : loudnessFilterDb;
    const matchesLoudness = s.peak_db >= effectiveGate;
    const matchesSearch =
      searchQuery === '' ||
      s.id.toLowerCase().includes(searchQuery.toLowerCase()) ||
      String(s.seq_index).includes(searchQuery);
    return matchesLoudness && matchesSearch;
  });

  return (
    <div className="flex flex-col h-full bg-[#080B11] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
      {/* Hidden Audio Element */}
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
      <input
        ref={restoreInputRef}
        type="file"
        accept="application/json"
        className="hidden"
        onChange={handleRestoreFile}
      />

      {/* Dashboard Top Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5 bg-[#0B0F19] border-b border-slate-800">
        <div className="flex items-center gap-3">
          <ShieldCheck className="w-5 h-5 text-cyan-400" />
          <div>
            <h2 className="text-sm font-bold text-white tracking-tight">APK 2: SAM&apos;S DASHBOARD v3.0</h2>
            <div className="flex items-center gap-2 text-xs text-slate-400 font-mono">
              <span>ARGON2ID + JWT CONSOLE</span>
              <span>·</span>
              <span className={`inline-flex items-center gap-1 ${wsConnected ? 'text-emerald-400' : 'text-amber-400'}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${wsConnected ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse'}`} />
                {wsConnected ? 'LIVE WS SYNC' : authToken ? 'RECONNECTING' : 'AUTH REQUIRED'}
              </span>
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          {backupStatusMsg && (
            <span className="px-2 py-1 rounded bg-cyan-950 text-cyan-300 border border-cyan-800 font-mono text-[11px]">
              {backupStatusMsg}
            </span>
          )}

          {storageStats && (
            <div className="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded bg-slate-900 border border-slate-800 font-mono text-slate-300">
              <HardDrive className="w-3.5 h-3.5 text-cyan-400" />
              <span>{storageStats.total_megabytes} MB</span>
              <span className="text-slate-500">({storageStats.total_segments} clips)</span>
            </div>
          )}

          {authToken ? (
            <>
              <button
                onClick={handleExportBackup}
                className="py-1 px-2.5 text-xs text-emerald-300 hover:text-white bg-emerald-950/60 border border-emerald-800/60 hover:bg-emerald-900 rounded transition-colors flex items-center gap-1"
                title="Export Portable JSON + Audio Snapshot"
              >
                <DatabaseBackup className="w-3.5 h-3.5" />
                <span className="hidden md:inline">Backup</span>
              </button>
              <button
                onClick={() => restoreInputRef.current?.click()}
                className="py-1 px-2.5 text-xs text-cyan-300 hover:text-white bg-cyan-950/60 border border-cyan-800/60 hover:bg-cyan-900 rounded transition-colors flex items-center gap-1"
                title="Restore Portable JSON + Audio Snapshot"
              >
                <Upload className="w-3.5 h-3.5" />
                <span className="hidden md:inline">Restore</span>
              </button>
              <button
                onClick={handleOpenAuditLogs}
                className="py-1 px-2.5 text-xs text-slate-300 hover:text-white bg-slate-800 hover:bg-slate-700 rounded transition-colors flex items-center gap-1"
                title="View Security Audit Trail"
              >
                <FileText className="w-3.5 h-3.5" />
                <span className="hidden md:inline">Audit</span>
              </button>
              <button
                onClick={handleLogout}
                className="py-1 px-2.5 text-xs text-rose-300 hover:text-white bg-rose-950/60 border border-rose-800/50 hover:bg-rose-900 rounded transition-colors flex items-center gap-1"
              >
                <LogOut className="w-3.5 h-3.5" />
                Logout
              </button>
            </>
          ) : (
            <button
              onClick={() => {
                setAuthMode(setupRequired ? 'setup' : 'login');
                setShowAuthModal(true);
              }}
              className="py-1.5 px-3 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-lg transition-colors flex items-center gap-1.5 shadow-md shadow-cyan-500/20"
            >
              <Lock className="w-3.5 h-3.5" />
              {setupRequired ? 'Create Admin Account' : 'Admin Sign In'}
            </button>
          )}
        </div>
      </div>

      {/* One-Time Recovery Key Banner after First-Time Setup */}
      {generatedRecoveryKey && (
        <div className="px-5 py-3 bg-emerald-950/90 border-b border-emerald-500/50 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex items-center gap-2 text-emerald-200">
            <KeyRound className="w-4 h-4 text-emerald-400 shrink-0" />
            <span>
              <strong>Save your One-Time Emergency Recovery Key:</strong>{' '}
              <code className="px-2 py-0.5 rounded bg-black/60 text-emerald-300 font-mono font-bold">
                {generatedRecoveryKey}
              </code>
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                navigator.clipboard.writeText(generatedRecoveryKey);
                setCopiedRecovery(true);
                setTimeout(() => setCopiedRecovery(false), 2000);
              }}
              className="px-2.5 py-1 rounded bg-emerald-800 hover:bg-emerald-700 text-white font-semibold flex items-center gap-1"
            >
              {copiedRecovery ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
              {copiedRecovery ? 'Copied' : 'Copy Key'}
            </button>
            <button
              onClick={() => setGeneratedRecoveryKey(null)}
              className="px-2 py-1 text-emerald-300 hover:text-white"
            >
              Dismiss
            </button>
          </div>
        </div>
      )}

      {/* Audit Logs Modal */}
      {showAuditModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="bg-[#0F172A] border border-slate-700 rounded-xl max-w-2xl w-full p-5 shadow-2xl max-h-[80vh] flex flex-col">
            <div className="flex items-center justify-between mb-3 pb-2 border-b border-slate-800">
              <h3 className="text-sm font-bold text-white flex items-center gap-2">
                <FileText className="w-4 h-4 text-cyan-400" />
                Security & Telemetry Audit Logs
              </h3>
              <button
                onClick={() => setShowAuditModal(false)}
                className="text-xs text-slate-400 hover:text-white px-2 py-1"
              >
                Close
              </button>
            </div>
            <div className="flex-1 overflow-y-auto space-y-1.5 font-mono text-[11px]">
              {auditLogs.length === 0 ? (
                <div className="text-slate-500 p-4 text-center">No audit logs found.</div>
              ) : (
                auditLogs.map((log, i) => (
                  <div key={i} className="p-2 rounded bg-slate-900/80 border border-slate-800 flex items-center justify-between gap-3">
                    <div>
                      <span className="text-cyan-400 font-bold">{log.action}</span>{' '}
                      <span className="text-slate-300">by {log.actor}</span>{' '}
                      <span className="text-slate-400">({log.details})</span>
                    </div>
                    <span className="text-slate-500 shrink-0">
                      {new Date(log.created_at * 1000).toLocaleTimeString()}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* Administrator Auth Modal */}
      {showAuthModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="bg-[#0F172A] border border-cyan-500/40 rounded-xl max-w-md w-full p-5 shadow-2xl">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-bold text-white">
                {authMode === 'setup'
                  ? 'First-Time Administrator Setup'
                  : authMode === 'reset'
                  ? 'Reset Password with Recovery Key'
                  : 'Administrator Sign In'}
              </h3>
              <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-800">
                Argon2id + JWT
              </span>
            </div>
            <p className="text-xs text-slate-400 mb-4">
              {authMode === 'setup'
                ? 'No default passwords exist in v3.0. Create your administrator credentials (minimum 10 characters) to secure telemetry access.'
                : authMode === 'reset'
                ? 'Enter your username, your one-time Emergency Recovery Key, and a new password.'
                : 'Sign in with your administrator credentials to inspect and play authorized recordings.'}
            </p>

            {authError && (
              <div className="p-2.5 mb-3 rounded bg-red-950/80 border border-red-800/60 text-xs text-red-300">
                {authError}
              </div>
            )}
            {authMessage && (
              <div className="p-2.5 mb-3 rounded bg-emerald-950/80 border border-emerald-800/60 text-xs text-emerald-300">
                {authMessage}
              </div>
            )}

            <form onSubmit={handleAuthSubmit} className="space-y-3">
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">Administrator Username</label>
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded p-2 text-xs text-white"
                  required
                />
              </div>

              {authMode === 'reset' && (
                <div>
                  <label className="text-[11px] font-mono text-amber-300 block mb-1">Emergency Recovery Key</label>
                  <input
                    type="text"
                    placeholder="SR-XXXX-XXXX-XXXX-XXXX"
                    value={recoveryKeyInput}
                    onChange={(e) => setRecoveryKeyInput(e.target.value)}
                    className="w-full bg-slate-900 border border-amber-700/60 rounded p-2 text-xs text-white font-mono"
                    required
                  />
                </div>
              )}

              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  {authMode === 'reset' ? 'New Password (min 10 chars)' : 'Password (min 10 chars)'}
                </label>
                <input
                  type="password"
                  placeholder="Enter strong password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded p-2 text-xs text-white"
                  required
                />
              </div>

              {authMode === 'setup' && (
                <div>
                  <label className="text-[11px] font-mono text-slate-400 block mb-1">Confirm Password</label>
                  <input
                    type="password"
                    placeholder="Confirm administrator password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="w-full bg-slate-900 border border-slate-700 rounded p-2 text-xs text-white"
                    required
                  />
                </div>
              )}

              <div className="flex items-center justify-between pt-2">
                <div className="flex gap-3 text-[11px]">
                  {authMode !== 'login' && !setupRequired && (
                    <button
                      type="button"
                      onClick={() => {
                        setAuthMode('login');
                        setAuthError(null);
                      }}
                      className="text-cyan-400 hover:underline"
                    >
                      Back to Login
                    </button>
                  )}
                  {authMode === 'login' && (
                    <button
                      type="button"
                      onClick={() => {
                        setAuthMode('reset');
                        setAuthError(null);
                      }}
                      className="text-amber-400 hover:underline"
                    >
                      Forgot Password?
                    </button>
                  )}
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setShowAuthModal(false)}
                    className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="px-4 py-1.5 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded"
                  >
                    {authMode === 'setup'
                      ? 'Complete Setup'
                      : authMode === 'reset'
                      ? 'Reset Password'
                      : 'Sign In'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Unauthenticated Gate Banner if not logged in */}
      {!authToken ? (
        <div className="flex-1 flex flex-col items-center justify-center p-8 text-center bg-[#090D16]">
          <div className="w-12 h-12 rounded-2xl bg-cyan-950/80 border border-cyan-500/40 flex items-center justify-center mb-4">
            <Lock className="w-6 h-6 text-cyan-400" />
          </div>
          <h3 className="text-base font-bold text-white mb-1">
            {setupRequired ? 'First-Time Administrator Setup Required' : 'Administrator Authentication Required'}
          </h3>
          <p className="text-xs text-slate-400 max-w-md mb-5 leading-relaxed">
            Sam&apos;s Dashboard v3.0 enforces zero default credentials, Argon2id password hashing, and authenticated JWT session verification.{' '}
            {setupRequired
              ? 'Create your administrator account now to unlock live audio telemetry and receive your Emergency Recovery Key.'
              : 'Sign in with your administrator password to inspect real authorized recording sessions, waveforms, and device status.'}
          </p>
          <div className="flex items-center gap-3">
            <button
              onClick={() => {
                setAuthMode(setupRequired ? 'setup' : 'login');
                setShowAuthModal(true);
              }}
              className="px-5 py-2.5 text-xs font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 rounded-xl transition-all shadow-lg shadow-cyan-500/20"
            >
              {setupRequired ? 'INITIALIZE ADMIN ACCOUNT' : 'SIGN IN TO CONSOLE'}
            </button>
            {!setupRequired && (
              <button
                onClick={() => {
                  setAuthMode('reset');
                  setShowAuthModal(true);
                }}
                className="px-4 py-2.5 text-xs font-semibold text-amber-300 bg-amber-950/60 border border-amber-800/60 hover:bg-amber-900/60 rounded-xl transition-all"
              >
                Use Recovery Key
              </button>
            )}
          </div>
        </div>
      ) : (
        /* Main Authenticated Dashboard Layout */
        <div className="grid grid-cols-1 lg:grid-cols-12 flex-1 overflow-hidden">
          {/* Left Column: Devices & Sessions List (4 cols) */}
          <div className="lg:col-span-4 border-r border-slate-800 bg-[#0A0E17] flex flex-col overflow-hidden">
            <div className="p-3 border-b border-slate-800 flex items-center justify-between text-xs">
              <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                <Layers className="w-4 h-4 text-cyan-400" />
                Authorized Sessions ({sessions.length})
              </span>
              <button
                onClick={() => fetchInitialData()}
                className="text-slate-400 hover:text-white transition-colors flex items-center gap-1 text-[11px]"
                title="Sync Telemetry"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                Sync
              </button>
            </div>

            {devices.length > 0 && (
              <div className="px-3 py-2 bg-slate-950/70 border-b border-slate-800/80 text-[11px] font-mono text-slate-400 flex items-center justify-between">
                <span>Enrolled Nodes: {devices.length}</span>
                <span className="text-emerald-400">{devices[0].name}</span>
              </div>
            )}

            <div className="flex-1 overflow-y-auto divide-y divide-slate-800/60 p-2 space-y-1.5">
              {sessions.length === 0 ? (
                <div className="p-6 text-center text-xs text-slate-500 font-mono leading-relaxed">
                  No authorized recording sessions uploaded yet. Activate a consent-verified session in Scream Runner to stream live speech segments.
                </div>
              ) : (
                sessions.map((sess) => (
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
                        {sess.device_name || 'Android Client'}
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
                      {new Date(sess.started_at * 1000).toLocaleTimeString([], {
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}{' '}
                      · {sess.total_speech_segments} speech clips
                    </div>

                    {sess.interruption_count ? (
                      <div className="text-[10px] text-amber-400 mt-1 font-mono flex items-center gap-1">
                        <AlertCircle className="w-3 h-3" />
                        {sess.interruption_count} interruption intervals logged
                      </div>
                    ) : null}
                  </div>
                ))
              )}
            </div>
          </div>

          {/* Right Column: Audio Inspector & Clips Grid (8 cols) */}
          <div className="lg:col-span-8 flex flex-col bg-[#0B0F19] overflow-hidden">
            {/* Active Player Deck */}
            <div className="p-5 border-b border-slate-800 bg-[#0E1420]/80 backdrop-blur-md">
              <div className="flex items-center justify-between text-xs text-slate-400 mb-2 font-mono">
                <span className="flex items-center gap-1.5 text-cyan-400 font-semibold">
                  <Activity className="w-4 h-4" />
                  AUTHENTICATED PCM / AAC WAVEFORM INSPECTOR
                </span>
                <span>
                  {selectedSegment ? (
                    <>
                      Seq #{selectedSegment.seq_index} · {selectedSegment.duration_ms} ms ·{' '}
                      <span className={selectedSegment.is_highlight ? 'text-pink-400 font-bold' : 'text-slate-300'}>
                        Peak {selectedSegment.peak_db.toFixed(1)} dB
                      </span>
                    </>
                  ) : (
                    'Select an authorized clip below'
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
                    disabled={!selectedSegment}
                    className="px-4 py-2 font-bold text-[#080B11] bg-cyan-400 hover:bg-cyan-300 disabled:opacity-40 rounded-lg transition-all flex items-center gap-2 shadow-md shadow-cyan-500/20"
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
                    href={getAuthenticatedDownloadUrl(selectedSegment.id)}
                    download
                    className="px-3 py-1.5 text-xs font-semibold text-emerald-400 bg-emerald-950/70 border border-emerald-800/60 rounded-lg hover:bg-emerald-900/60 transition-colors flex items-center gap-1.5"
                  >
                    <Download className="w-3.5 h-3.5" />
                    Export Clip
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
                  placeholder="Search clip ID or sequence..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="bg-transparent border-none outline-none text-white text-xs w-full placeholder:text-slate-600"
                />
              </div>

              <div className="flex flex-wrap items-center gap-3">
                <button
                  onClick={() => setPeaksOnlyFilter((p) => !p)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono font-semibold border transition-colors ${
                    peaksOnlyFilter
                      ? 'bg-pink-950 text-pink-300 border-pink-600'
                      : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                  }`}
                >
                  {peaksOnlyFilter ? 'PEAKS ONLY (> -10 dB)' : 'ALL CLIPS'}
                </button>

                <div className="flex items-center gap-2">
                  <span className="text-slate-400 text-xs">Gate:</span>
                  <input
                    type="range"
                    min="-45"
                    max="-5"
                    step="1"
                    value={loudnessFilterDb}
                    onChange={(e) => setLoudnessFilterDb(Number(e.target.value))}
                    className="w-20 accent-pink-500 cursor-pointer"
                  />
                  <span className="font-mono text-pink-400 font-semibold">{loudnessFilterDb} dB</span>
                </div>
              </div>
            </div>

            {/* Speech Clips Grid */}
            <div className="flex-1 overflow-y-auto p-4 space-y-2">
              {filteredSegments.length === 0 ? (
                <div className="p-8 text-center text-slate-500 text-xs font-mono">
                  No uploaded clips match the current filter criteria.
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
                          <span>Speech Clip ({seg.codec || 'WAV/AAC'})</span>
                          {seg.is_highlight && (
                            <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-pink-950 text-pink-400 border border-pink-800/50 flex items-center gap-1">
                              <Sparkles className="w-2.5 h-2.5" />
                              PEAK {seg.peak_db.toFixed(1)} dB
                            </span>
                          )}
                        </div>
                        <div className="text-[11px] text-slate-400 font-mono">
                          {seg.duration_ms} ms · Avg: {seg.avg_db.toFixed(1)} dB · SHA-256:{' '}
                          {seg.file_hash ? `${seg.file_hash.slice(0, 10)}...` : 'verified'}
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
                        title="Delete Clip"
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
      )}
    </div>
  );
};
