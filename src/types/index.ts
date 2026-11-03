export interface Device {
  id: string;
  name: string;
  device_token: string;
  registered_at: number;
  last_ping: number;
  status: 'online' | 'recording' | 'idle';
}

export interface Session {
  id: string;
  device_id: string;
  device_name?: string;
  started_at: number;
  ended_at?: number;
  duration_seconds: number;
  consent_confirmed: number;
  total_speech_segments: number;
  status: 'active' | 'stopped' | 'interrupted';
  interruption_count?: number;
}

export interface AudioSegment {
  id: string;
  session_id: string;
  seq_index: number;
  start_time: number;
  end_time: number;
  duration_ms: number;
  peak_db: number;
  avg_db: number;
  speech_confidence: number;
  file_size_bytes: number;
  created_at: number;
  waveform_data?: string;
  is_highlight?: boolean;
  codec?: string;
  file_hash?: string;
}

export interface TestResult {
  round: number;
  title: string;
  status: 'pending' | 'running' | 'passed' | 'failed';
  details: string;
  durationMs?: number;
  evidence?: Record<string, any>;
}
