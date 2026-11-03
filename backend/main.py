import os
import sys
import time
import uuid
import json
import math
import struct
import sqlite3
import hashlib
import asyncio
from pathlib import Path
from typing import Optional, List, Dict, Any

from fastapi import FastAPI, UploadFile, File, Form, Depends, HTTPException, status, Header, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, Response, FileResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import jwt

# Environment and Path setup
BASE_DIR = Path(__file__).resolve().parent
STORAGE_DIR = BASE_DIR / "storage"
AUDIO_STORAGE_DIR = STORAGE_DIR / "audio"
DB_PATH = STORAGE_DIR / "scream_runner.db"

AUDIO_STORAGE_DIR.mkdir(parents=True, exist_ok=True)

# Security Secrets
SECRET_FILE = STORAGE_DIR / ".admin_secret"
DEFAULT_ADMIN_PASS = "screamrunner2026!"

def load_or_generate_secret() -> str:
    # Fail closed: no known default or on-disk plaintext password fallback.
    password = os.environ.get("ADMIN_PASSWORD", "")
    if len(password) < 16:
        raise RuntimeError("ADMIN_PASSWORD must be set to a unique value of at least 16 characters")
    return password

JWT_SECRET = os.environ.get("JWT_SECRET", "")
if len(JWT_SECRET) < 32:
    raise RuntimeError("JWT_SECRET must be configured with at least 32 characters")
JWT_ALGORITHM = "HS256"
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = load_or_generate_secret()

# Failed login attempt tracking (Rate Limiting)
failed_login_attempts: Dict[str, List[float]] = {}
RATE_LIMIT_WINDOW_SEC = 60
MAX_FAILED_ATTEMPTS = 5

# Global event sequence counter
event_seq_counter = 0

def get_db_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA foreign_keys = ON;")
    return conn

# Database Initialization with WAL Mode
def init_db():
    conn = get_db_conn()
    with conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            device_token TEXT NOT NULL UNIQUE,
            registered_at REAL NOT NULL,
            last_ping REAL NOT NULL,
            status TEXT NOT NULL DEFAULT 'online'
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            started_at REAL NOT NULL,
            ended_at REAL,
            duration_seconds REAL DEFAULT 0,
            consent_confirmed INTEGER NOT NULL DEFAULT 1,
            total_speech_segments INTEGER DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'active',
            FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS audio_segments (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            seq_index INTEGER NOT NULL,
            start_time REAL NOT NULL,
            end_time REAL NOT NULL,
            duration_ms INTEGER NOT NULL,
            peak_db REAL NOT NULL,
            avg_db REAL NOT NULL,
            speech_confidence REAL NOT NULL,
            file_path TEXT NOT NULL,
            file_hash TEXT NOT NULL,
            file_size_bytes INTEGER NOT NULL,
            created_at REAL NOT NULL,
            waveform_data TEXT,
            FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE,
            UNIQUE(session_id, file_hash)
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS events (
            id TEXT PRIMARY KEY,
            event_seq INTEGER NOT NULL,
            session_id TEXT,
            event_type TEXT NOT NULL,
            timestamp REAL NOT NULL,
            details TEXT
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS revoked_tokens (
            jti TEXT PRIMARY KEY,
            revoked_at REAL NOT NULL
        );
        """)

    # Seed initial verified demo session if database has no sessions yet
    count = conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    conn.close()
    # Never inject synthetic recordings into a production database.
    # Test fixtures must be created explicitly in isolated test databases.

def seed_demo_data() -> dict:
    conn = get_db_conn()
    now = time.time()
    dev_id = "dev_cyber_pixel_7"
    conn.execute("""
        INSERT OR IGNORE INTO devices (id, name, device_token, registered_at, last_ping, status)
        VALUES (?, 'Pixel 7 Pro (Android 13)', 'dev_tok_demo_pixel_7', ?, ?, 'recording')
    """, (dev_id, now - 7200, now))
    conn.execute("UPDATE devices SET last_ping = ?, status = 'recording' WHERE id = ?", (now, dev_id))

    session_id = "sess_demo_run_01"
    conn.execute("""
        INSERT OR REPLACE INTO sessions (id, device_id, started_at, ended_at, duration_seconds, consent_confirmed, total_speech_segments, status)
        VALUES (?, ?, ?, ?, 3600, 1, 4, 'active')
    """, (session_id, dev_id, now - 3600, None))

    session_dir = AUDIO_STORAGE_DIR / session_id
    session_dir.mkdir(parents=True, exist_ok=True)

    bursts = [
        {"idx": 1, "start_offset": 120, "dur": 3200, "peak": -8.4, "avg": -18.2, "freq": 440, "desc": "Loud shout 'JUMP NOW'"},
        {"idx": 2, "start_offset": 450, "dur": 2400, "peak": -14.1, "avg": -22.5, "freq": 330, "desc": "Conversational speech segment"},
        {"idx": 3, "start_offset": 890, "dur": 4100, "peak": -5.2, "avg": -15.8, "freq": 520, "desc": "High-volume peak moment (> -10dB)"},
        {"idx": 4, "start_offset": 1420, "dur": 1800, "peak": -19.3, "avg": -27.0, "freq": 280, "desc": "Whisper / soft audio command"}
    ]

    for b in bursts:
        seg_id = f"seg_demo_{b['idx']}"
        file_path = session_dir / f"{seg_id}.wav"

        sample_rate = 16000
        total_samples = int(sample_rate * (b['dur'] / 1000.0))
        pcm_data = bytearray()

        waveform_bars = []
        bars_count = 60
        for bar_i in range(bars_count):
            bar_env = math.sin((bar_i / bars_count) * math.pi) * (10 ** (b['peak'] / 20.0))
            waveform_bars.append(round(min(1.0, max(0.05, bar_env + 0.1 * math.sin(bar_i * 1.5))), 3))

        for s in range(total_samples):
            t = s / sample_rate
            env = math.sin((s / total_samples) * math.pi)
            sample_val = int(32767 * env * (10 ** (b['peak'] / 20.0)) * math.sin(2 * math.pi * b['freq'] * t))
            sample_val = max(-32768, min(32767, sample_val))
            pcm_data.extend(sample_val.to_bytes(2, byteorder='little', signed=True))

        header = struct.pack(
            '<4sI4s4sIHHIIHH4sI',
            b'RIFF',
            36 + len(pcm_data),
            b'WAVE',
            b'fmt ',
            16,
            1,
            1,
            sample_rate,
            sample_rate * 2,
            2,
            16,
            b'data',
            len(pcm_data)
        )
        full_wav = header + pcm_data
        with open(file_path, "wb") as f:
            f.write(full_wav)

        file_size = len(full_wav)
        f_hash = hashlib.sha256(full_wav).hexdigest()

        conn.execute("""
            INSERT OR REPLACE INTO audio_segments (
                id, session_id, seq_index, start_time, end_time, duration_ms,
                peak_db, avg_db, speech_confidence, file_path, file_hash, file_size_bytes,
                created_at, waveform_data
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            seg_id, session_id, b['idx'], now - (3600 - b['start_offset']),
            now - (3600 - b['start_offset']) + (b['dur'] / 1000.0),
            b['dur'], b['peak'], b['avg'], 0.94, str(file_path), f_hash, file_size,
            now - (3600 - b['start_offset']), json.dumps(waveform_bars)
        ))

    conn.commit()
    conn.close()
    return {"status": "seeded", "session_id": session_id, "segments": 4}

init_db()

# FastAPI App
app = FastAPI(title="Scream Runner & Sam's Dashboard Backend", version="2.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Active WebSocket connections
class ConnectionManager:
    def __init__(self):
        self.active_connections: List[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, message: dict):
        dead_connections = []
        for connection in self.active_connections:
            try:
                await connection.send_text(json.dumps(message))
            except Exception:
                dead_connections.append(connection)
        for dc in dead_connections:
            self.disconnect(dc)

manager = ConnectionManager()

def record_event(conn: sqlite3.Connection, event_type: str, session_id: Optional[str] = None, details: Optional[dict] = None) -> int:
    global event_seq_counter
    event_seq_counter += 1
    event_id = str(uuid.uuid4())
    now = time.time()
    details_str = json.dumps(details or {})
    conn.execute(
        "INSERT INTO events (id, event_seq, session_id, event_type, timestamp, details) VALUES (?, ?, ?, ?, ?, ?)",
        (event_id, event_seq_counter, session_id, event_type, now, details_str)
    )
    conn.commit()
    return event_seq_counter

# JWT Authentication
def create_jwt_token(subject: str, role: str = "admin", expires_in_seconds: int = 86400) -> str:
    jti = str(uuid.uuid4())
    now = int(time.time())
    payload = {
        "sub": subject,
        "role": role,
        "jti": jti,
        "iat": now,
        "exp": now + expires_in_seconds
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)

def verify_token(authorization: Optional[str] = Header(None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing or invalid Authorization header")
    token = authorization[7:].strip()
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        jti = payload.get("jti")
        conn = get_db_conn()
        revoked = conn.execute("SELECT 1 FROM revoked_tokens WHERE jti = ?", (jti,)).fetchone()
        conn.close()
        if revoked:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token has been revoked")
        if payload.get("role") != "admin":
            raise HTTPException(status_code=403, detail="Administrator access required")
        return payload
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token has expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token signature")

def verify_optional_token(authorization: Optional[str] = Header(None)) -> Optional[dict]:
    """If an Authorization Bearer header is provided, strictly validates it (rejecting revoked/invalid tokens)."""
    if authorization and authorization.startswith("Bearer "):
        token = authorization[7:].strip()
        if token and token != "null" and token != "undefined":
            return verify_token(authorization)
    return None

# Models
class LoginRequest(BaseModel):
    username: str
    password: str

class RefreshRequest(BaseModel):
    refresh_token: str

class DeviceRegisterRequest(BaseModel):
    id: Optional[str] = None
    name: str

class SessionCreateRequest(BaseModel):
    session_id: Optional[str] = None
    device_id: str
    device_name: Optional[str] = None
    consent_confirmed: bool = True

class SessionStopRequest(BaseModel):
    session_id: str

# ----------------- Auth Endpoints -----------------
@app.post("/api/v1/auth/login")
def login(req: LoginRequest, request: Request):
    client_ip = request.client.host if request.client else "unknown"
    now = time.time()

    # Only the explicitly configured administrator credential is accepted.
    valid_passwords = {ADMIN_PASSWORD}

    # Check if valid credentials first (clears rate limit bucket for legitimate admin)
    if req.username == ADMIN_USERNAME and req.password in valid_passwords:
        failed_login_attempts.pop(client_ip, None)
        access_token = create_jwt_token(req.username, role="admin", expires_in_seconds=86400)
        refresh_token = create_jwt_token(req.username, role="refresh", expires_in_seconds=604800)
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "bearer",
            "expires_in": 86400,
            "user": {"username": req.username, "role": "admin"}
        }

    # Check rate limit for failed attempts
    attempts = [t for t in failed_login_attempts.get(client_ip, []) if now - t < RATE_LIMIT_WINDOW_SEC]
    failed_login_attempts[client_ip] = attempts
    if len(attempts) >= MAX_FAILED_ATTEMPTS:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many failed login attempts. Please wait 60 seconds before retrying."
        )

    # Record failed attempt
    failed_login_attempts.setdefault(client_ip, []).append(now)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid administrator credentials")

@app.post("/api/v1/auth/refresh")
def refresh_token_endpoint(req: RefreshRequest):
    try:
        payload = jwt.decode(req.refresh_token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="Invalid refresh token")
    conn = get_db_conn()
    revoked = conn.execute("SELECT 1 FROM revoked_tokens WHERE jti = ?", (payload.get("jti"),)).fetchone()
    conn.close()
    if revoked:
        raise HTTPException(status_code=401, detail="Revoked refresh token")
    if payload.get("role") != "refresh" or payload.get("sub") != ADMIN_USERNAME:
        raise HTTPException(status_code=401, detail="Invalid refresh token")
    username = payload["sub"]
    new_access_token = create_jwt_token(username, role="admin", expires_in_seconds=86400)
    return {
        "access_token": new_access_token,
        "token_type": "bearer",
        "expires_in": 86400
    }

@app.get("/api/v1/auth/me")
def get_current_user(payload: dict = Depends(verify_token)):
    return {
        "authenticated": True,
        "username": payload.get("sub"),
        "role": payload.get("role"),
        "jti": payload.get("jti")
    }

@app.get("/api/v1/auth/credentials-info")
def credentials_info():
    is_custom_env = "ADMIN_PASSWORD" in os.environ and bool(os.environ["ADMIN_PASSWORD"])
    return {
        "admin_username": ADMIN_USERNAME,
        "configured_via_env": is_custom_env,
        "secret_file_persisted": SECRET_FILE.exists(),
        "auth_required": True,
        "note": "Credentials set via ADMIN_PASSWORD environment variable or local encrypted secret."
    }

@app.post("/api/v1/auth/logout")
def logout(authorization: Optional[str] = Header(None)):
    if not authorization or not authorization.startswith("Bearer "):
        return {"status": "ok", "message": "No token provided"}
    token = authorization[7:].strip()
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        jti = payload.get("jti")
        if jti:
            conn = get_db_conn()
            conn.execute("INSERT OR IGNORE INTO revoked_tokens (jti, revoked_at) VALUES (?, ?)", (jti, time.time()))
            conn.commit()
            conn.close()
    except Exception:
        pass
    return {"status": "ok", "message": "Logged out successfully and token revoked"}

# ----------------- APK Downloads -----------------
PUBLIC_DOWNLOADS_DIR = BASE_DIR.parent / "public" / "downloads"

@app.get("/api/v1/downloads/info")
def get_downloads_info():
    apk_files = ["scream-runner.apk", "sams-dashboard.apk", "scream-runner-v2.4.0.apk", "sams-dashboard-v2.4.0.apk"]
    info = []
    for fname in apk_files:
        fpath = PUBLIC_DOWNLOADS_DIR / fname
        if fpath.exists():
            data = fpath.read_bytes()
            info.append({
                "filename": fname,
                "size_bytes": len(data),
                "size_mb": round(len(data) / (1024 * 1024), 2),
                "sha256": hashlib.sha256(data).hexdigest(),
                "download_url": f"/downloads/{fname}",
                "api_download_url": f"/api/v1/downloads/{fname}"
            })
    return {"downloads": info}

@app.get("/api/v1/downloads/{filename}")
def download_apk(filename: str):
    if filename.startswith("scream-runner") and filename.endswith(".apk"):
        disk_name = filename if (PUBLIC_DOWNLOADS_DIR / filename).exists() else "scream-runner.apk"
    elif filename.startswith("sams-dashboard") and filename.endswith(".apk"):
        disk_name = filename if (PUBLIC_DOWNLOADS_DIR / filename).exists() else "sams-dashboard.apk"
    else:
        raise HTTPException(status_code=404, detail="Requested APK file not found")

    fpath = PUBLIC_DOWNLOADS_DIR / disk_name
    if not fpath.exists():
        raise HTTPException(status_code=404, detail="APK file not found on server")

    file_bytes = fpath.read_bytes()
    return Response(
        content=file_bytes,
        media_type="application/vnd.android.package-archive",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Content-Length": str(len(file_bytes)),
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Expose-Headers": "Content-Disposition, Content-Length",
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "Expires": "0",
            "X-Content-Type-Options": "nosniff"
        }
    )

# ----------------- Device Management -----------------
@app.post("/api/v1/devices/register")
async def register_device(req: DeviceRegisterRequest):
    conn = get_db_conn()
    now = time.time()
    # Reuse existing device if same id or name exists
    existing = None
    if req.id:
        existing = conn.execute("SELECT id, device_token FROM devices WHERE id = ?", (req.id,)).fetchone()
    if not existing:
        existing = conn.execute("SELECT id, device_token FROM devices WHERE name = ?", (req.name,)).fetchone()

    if existing:
        device_id = existing["id"]
        token = existing["device_token"]
        conn.execute("UPDATE devices SET last_ping = ?, status = 'online', name = ? WHERE id = ?", (now, req.name, device_id))
    else:
        device_id = req.id or f"dev_{uuid.uuid4().hex[:10]}"
        token = f"dev_tok_{hashlib.sha256(f'{device_id}{now}'.encode()).hexdigest()[:32]}"
        conn.execute(
            "INSERT INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, ?)",
            (device_id, req.name, token, now, now, "online")
        )
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "DEVICE_REGISTERED",
        "device_id": device_id,
        "name": req.name,
        "status": "online",
        "last_ping": now
    })
    return {"device_id": device_id, "device_token": token, "status": "registered"}

@app.post("/api/v1/devices/{device_id}/ping")
async def ping_device(device_id: str, status_val: str = "online", name: Optional[str] = None):
    conn = get_db_conn()
    now = time.time()
    existing = conn.execute("SELECT id FROM devices WHERE id = ?", (device_id,)).fetchone()
    if existing:
        conn.execute("UPDATE devices SET last_ping = ?, status = ? WHERE id = ?", (now, status_val, device_id))
    else:
        dev_name = name or f"Android Device ({device_id[:8]})"
        token = f"dev_tok_{hashlib.sha256(f'{device_id}{now}'.encode()).hexdigest()[:32]}"
        conn.execute(
            "INSERT INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, ?)",
            (device_id, dev_name, token, now, now, status_val)
        )
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "DEVICE_PING",
        "device_id": device_id,
        "status": status_val,
        "last_ping": now
    })
    return {"status": "ok", "device_id": device_id, "last_ping": now}

@app.get("/api/v1/devices")
def list_devices(auth: Optional[dict] = Depends(verify_optional_token)):
    conn = get_db_conn()
    rows = conn.execute("SELECT id, name, registered_at, last_ping, status FROM devices ORDER BY last_ping DESC").fetchall()
    devices = [dict(r) for r in rows]
    conn.close()
    return {"devices": devices}

# ----------------- Sessions -----------------
@app.post("/api/v1/sessions/start")
async def start_session(req: SessionCreateRequest):
    conn = get_db_conn()
    now = time.time()

    # Ensure parent device exists so foreign key constraint never fails
    dev_row = conn.execute("SELECT id, name FROM devices WHERE id = ?", (req.device_id,)).fetchone()
    dev_name = req.device_name or (dev_row["name"] if dev_row else f"Android Device ({req.device_id[:10]})")
    if not dev_row:
        tok = f"dev_tok_{hashlib.sha256(f'{req.device_id}{now}'.encode()).hexdigest()[:32]}"
        conn.execute(
            "INSERT OR IGNORE INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, 'recording')",
            (req.device_id, dev_name, tok, now, now)
        )
    else:
        conn.execute("UPDATE devices SET last_ping = ?, status = 'recording' WHERE id = ?", (now, req.device_id))

    session_id = req.session_id or f"sess_{uuid.uuid4().hex[:10]}"
    conn.execute(
        """INSERT OR REPLACE INTO sessions (id, device_id, started_at, consent_confirmed, status, total_speech_segments)
           VALUES (?, ?, ?, ?, 'active', COALESCE((SELECT total_speech_segments FROM sessions WHERE id = ?), 0))""",
        (session_id, req.device_id, now, 1 if req.consent_confirmed else 0, session_id)
    )
    seq = record_event(conn, "SESSION_START", session_id, {"device_id": req.device_id, "device_name": dev_name, "started_at": now})
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "SESSION_START",
        "seq": seq,
        "session_id": session_id,
        "device_id": req.device_id,
        "device_name": dev_name,
        "started_at": now
    })
    return {"session_id": session_id, "device_id": req.device_id, "status": "active", "started_at": now}

@app.post("/api/v1/sessions/stop")
async def stop_session(req: SessionStopRequest):
    conn = get_db_conn()
    now = time.time()
    row = conn.execute("SELECT started_at, device_id FROM sessions WHERE id = ?", (req.session_id,)).fetchone()
    if not row:
        conn.close()
        raise HTTPException(status_code=404, detail="Session not found")

    duration = max(0.0, now - row["started_at"])
    conn.execute(
        "UPDATE sessions SET ended_at = ?, duration_seconds = ?, status = 'completed' WHERE id = ?",
        (now, duration, req.session_id)
    )
    conn.execute("UPDATE devices SET last_ping = ?, status = 'online' WHERE id = ?", (now, row["device_id"]))
    seq = record_event(conn, "SESSION_STOP", req.session_id, {"ended_at": now, "duration_seconds": duration})
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "SESSION_STOP",
        "seq": seq,
        "session_id": req.session_id,
        "ended_at": now,
        "duration_seconds": duration
    })
    return {"session_id": req.session_id, "status": "completed", "duration_seconds": round(duration, 2)}

@app.get("/api/v1/sessions")
def list_sessions(auth: Optional[dict] = Depends(verify_optional_token)):
    conn = get_db_conn()
    rows = conn.execute("""
        SELECT s.id, s.device_id, d.name as device_name, s.started_at, s.ended_at,
               s.duration_seconds, s.consent_confirmed, s.total_speech_segments, s.status
        FROM sessions s
        LEFT JOIN devices d ON s.device_id = d.id
        ORDER BY s.started_at DESC
    """).fetchall()
    sessions = []
    for r in rows:
        item = dict(r)
        gap_count = conn.execute(
            "SELECT COUNT(*) FROM events WHERE session_id = ? AND event_type IN ('INTERRUPTION', 'NETWORK_LOSS')",
            (item["id"],)
        ).fetchone()[0]
        item["interruption_count"] = gap_count
        sessions.append(item)
    conn.close()
    return {"sessions": sessions}

def compute_waveform_from_wav_bytes(contents: bytes, num_bars: int = 50) -> str:
    """Computes normalized 50-bar RMS waveform directly from PCM WAV bytes if not provided by client."""
    pcm = contents[44:] if len(contents) > 44 and contents[:4] == b'RIFF' else contents
    total_samples = len(pcm) // 2
    if total_samples <= 0:
        return "[0.2,0.4,0.7,0.5,0.3]"
    samples_per_bar = max(1, total_samples // num_bars)
    bars = []
    for b in range(num_bars):
        start = b * samples_per_bar
        end = min(start + samples_per_bar, total_samples)
        sum_sq = 0.0
        count = end - start
        for i in range(start, end):
            sample = int.from_bytes(pcm[i * 2: i * 2 + 2], byteorder='little', signed=True)
            sum_sq += sample * sample
        rms = math.sqrt(sum_sq / count) if count > 0 else 0.0
        bars.append(round(min(1.0, max(0.05, (rms / 32768.0) * 3.0)), 3))
    return json.dumps(bars)

# ----------------- Audio Upload & Ingestion -----------------
@app.post("/api/v1/audio/upload")
async def upload_audio_segment(
    audio_file: UploadFile = File(...),
    session_id: str = Form(...),
    seq_index: int = Form(...),
    start_time: float = Form(...),
    end_time: float = Form(...),
    duration_ms: int = Form(...),
    peak_db: float = Form(...),
    avg_db: float = Form(...),
    speech_confidence: float = Form(0.95),
    file_hash: Optional[str] = Form(None),
    waveform_json: Optional[str] = Form(None),
    device_id: Optional[str] = Form(None),
    device_name: Optional[str] = Form(None)
):
    try:
        contents = await audio_file.read()
        computed_hash = hashlib.sha256(contents).hexdigest()

        if file_hash and file_hash != computed_hash:
            raise HTTPException(status_code=400, detail="SHA-256 integrity verification failed")

        final_hash = computed_hash
        conn = get_db_conn()

        # Idempotency check: duplicate hash within this session
        existing = conn.execute(
            "SELECT id, file_path FROM audio_segments WHERE session_id = ? AND file_hash = ?",
            (session_id, final_hash)
        ).fetchone()

        if existing:
            conn.close()
            return {
                "status": "already_exists",
                "segment_id": existing["id"],
                "message": "Segment already ingested with identical SHA-256 hash (idempotent)."
            }

        segment_id = str(uuid.uuid4())
        session_audio_dir = AUDIO_STORAGE_DIR / session_id
        session_audio_dir.mkdir(parents=True, exist_ok=True)

        ext = Path(audio_file.filename).suffix if audio_file.filename else ".wav"
        if not ext:
            ext = ".wav"
        dest_path = session_audio_dir / f"{segment_id}{ext}"
        with open(dest_path, "wb") as f:
            f.write(contents)

        now = time.time()
        resolved_device_id = device_id or "dev_auto_sync"
        resolved_device_name = device_name or "Scream Runner Android Device"

        # 1. Ensure parent device exists in devices table (satisfies FK on sessions.device_id)
        dev_exists = conn.execute("SELECT id FROM devices WHERE id = ?", (resolved_device_id,)).fetchone()
        if not dev_exists:
            tok = f"dev_tok_{hashlib.sha256(f'{resolved_device_id}{now}'.encode()).hexdigest()[:32]}"
            conn.execute(
                "INSERT OR IGNORE INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, 'recording')",
                (resolved_device_id, resolved_device_name, tok, now, now)
            )
        else:
            conn.execute("UPDATE devices SET last_ping = ?, status = 'recording' WHERE id = ?", (now, resolved_device_id))

        # 2. Ensure parent session exists in sessions table (satisfies FK on audio_segments.session_id)
        conn.execute("""
            INSERT OR IGNORE INTO sessions (id, device_id, started_at, consent_confirmed, status)
            VALUES (?, ?, ?, 1, 'active')
        """, (session_id, resolved_device_id, now))

        final_waveform = waveform_json if waveform_json else compute_waveform_from_wav_bytes(contents)

        conn.execute("""
            INSERT INTO audio_segments (
                id, session_id, seq_index, start_time, end_time, duration_ms,
                peak_db, avg_db, speech_confidence, file_path, file_hash, file_size_bytes,
                created_at, waveform_data
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            segment_id, session_id, seq_index, start_time, end_time, duration_ms,
            peak_db, avg_db, speech_confidence, str(dest_path), final_hash, len(contents),
            now, final_waveform
        ))
        conn.execute("UPDATE sessions SET total_speech_segments = total_speech_segments + 1 WHERE id = ?", (session_id,))

        is_highlight = peak_db > -10.0
        seq = record_event(conn, "AUDIO_UPLOADED", session_id, {
            "segment_id": segment_id,
            "peak_db": peak_db,
            "duration_ms": duration_ms,
            "is_highlight": is_highlight
        })
        conn.commit()
        conn.close()

        await manager.broadcast({
            "type": "AUDIO_UPLOADED",
            "seq": seq,
            "session_id": session_id,
            "segment_id": segment_id,
            "seq_index": seq_index,
            "peak_db": peak_db,
            "avg_db": avg_db,
            "duration_ms": duration_ms,
            "start_time": start_time,
            "end_time": end_time,
            "is_highlight": is_highlight,
            "waveform_data": final_waveform
        })

        return {
            "status": "uploaded",
            "segment_id": segment_id,
            "session_id": session_id,
            "file_hash": final_hash,
            "is_highlight": is_highlight
        }
    except HTTPException:
        raise
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/api/v1/sessions/{session_id}/segments")
def get_session_segments(session_id: str, auth: Optional[dict] = Depends(verify_optional_token)):
    conn = get_db_conn()
    rows = conn.execute("""
        SELECT id, session_id, seq_index, start_time, end_time, duration_ms,
               peak_db, avg_db, speech_confidence, file_size_bytes, created_at, waveform_data
        FROM audio_segments
        WHERE session_id = ?
        ORDER BY seq_index ASC, created_at ASC
    """, (session_id,)).fetchall()
    segments = []
    for r in rows:
        item = dict(r)
        item["is_highlight"] = item["peak_db"] > -10.0
        segments.append(item)
    conn.close()
    return {"segments": segments}

# ----------------- HTTP 206 Byte-Range Audio Streaming & Download -----------------
@app.get("/api/v1/audio/{segment_id}/stream")
def stream_audio(segment_id: str, request: Request):
    conn = get_db_conn()
    row = conn.execute("SELECT file_path, file_size_bytes FROM audio_segments WHERE id = ?", (segment_id,)).fetchone()
    conn.close()
    if not row or not os.path.exists(row["file_path"]):
        raise HTTPException(status_code=404, detail="Audio file not found")

    file_path = row["file_path"]
    file_size = os.path.getsize(file_path)
    content_type = "audio/mp4" if file_path.endswith(".m4a") else "audio/wav"

    range_header = request.headers.get("range")
    if not range_header:
        return FileResponse(
            file_path,
            media_type=content_type,
            headers={
                "Accept-Ranges": "bytes",
                "Content-Length": str(file_size),
                "Access-Control-Allow-Origin": "*"
            }
        )

    try:
        byte_range = range_header.replace("bytes=", "").split("-")
        start = int(byte_range[0])
        end = int(byte_range[1]) if byte_range[1] else file_size - 1
    except ValueError:
        raise HTTPException(status_code=416, detail="Invalid Range header")

    if start >= file_size or end >= file_size or start > end:
        raise HTTPException(status_code=416, detail="Requested range not satisfiable")

    chunk_size = (end - start) + 1

    def iterfile():
        with open(file_path, "rb") as f:
            f.seek(start)
            bytes_left = chunk_size
            while bytes_left > 0:
                read_len = min(65536, bytes_left)
                data = f.read(read_len)
                if not data:
                    break
                bytes_left -= len(data)
                yield data

    headers = {
        "Content-Range": f"bytes {start}-{end}/{file_size}",
        "Accept-Ranges": "bytes",
        "Content-Length": str(chunk_size),
        "Content-Type": content_type,
        "Access-Control-Allow-Origin": "*",
    }
    return StreamingResponse(iterfile(), status_code=206, headers=headers)

@app.get("/api/v1/audio/{segment_id}/download")
def download_audio(segment_id: str):
    conn = get_db_conn()
    row = conn.execute("SELECT file_path, seq_index, session_id FROM audio_segments WHERE id = ?", (segment_id,)).fetchone()
    conn.close()
    if not row or not os.path.exists(row["file_path"]):
        raise HTTPException(status_code=404, detail="Audio file not found")

    file_path = row["file_path"]
    ext = Path(file_path).suffix or ".wav"
    media_type = "audio/mp4" if ext == ".m4a" else "audio/wav"
    filename = f"speech_segment_{row['session_id']}_{segment_id[:8]}{ext}"
    return FileResponse(file_path, filename=filename, media_type=media_type)

# ----------------- Missed Events & WebSocket -----------------
@app.get("/api/v1/events/missed")
def get_missed_events(since_seq: int = 0, session_id: Optional[str] = None):
    conn = get_db_conn()
    query = "SELECT id, event_seq, session_id, event_type, timestamp, details FROM events WHERE event_seq > ?"
    params: List[Any] = [since_seq]
    if session_id:
        query += " AND session_id = ?"
        params.append(session_id)
    query += " ORDER BY event_seq ASC LIMIT 100"
    rows = conn.execute(query, params).fetchall()
    events = [dict(r) for r in rows]
    conn.close()
    return {"missed_events": events, "latest_seq": event_seq_counter}

@app.websocket("/ws/live-feed")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    await websocket.send_text(json.dumps({
        "type": "CONNECTED",
        "latest_seq": event_seq_counter,
        "timestamp": time.time()
    }))
    try:
        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
                if msg.get("type") == "PING":
                    await websocket.send_text(json.dumps({"type": "PONG", "timestamp": time.time()}))
            except Exception:
                pass
    except WebSocketDisconnect:
        manager.disconnect(websocket)

# ----------------- Storage Management & Health -----------------
@app.get("/api/v1/storage/stats")
def storage_stats():
    conn = get_db_conn()
    seg_stats = conn.execute("SELECT COUNT(*), COALESCE(SUM(file_size_bytes), 0) FROM audio_segments").fetchone()
    session_count = conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    active_sessions = conn.execute("SELECT COUNT(*) FROM sessions WHERE status = 'active'").fetchone()[0]
    conn.close()

    total_segments = seg_stats[0]
    total_bytes = seg_stats[1]

    return {
        "total_segments": total_segments,
        "total_bytes": total_bytes,
        "total_megabytes": round(total_bytes / (1024 * 1024), 2),
        "total_sessions": session_count,
        "active_sessions": active_sessions
    }

@app.delete("/api/v1/audio/{segment_id}")
async def delete_segment(segment_id: str, auth: Optional[dict] = Depends(verify_optional_token)):
    conn = get_db_conn()
    row = conn.execute("SELECT file_path, session_id FROM audio_segments WHERE id = ?", (segment_id,)).fetchone()
    if not row:
        conn.close()
        raise HTTPException(status_code=404, detail="Segment not found")

    file_path = row["file_path"]
    session_id = row["session_id"]
    if os.path.exists(file_path):
        try:
            os.remove(file_path)
        except Exception:
            pass

    conn.execute("DELETE FROM audio_segments WHERE id = ?", (segment_id,))
    conn.execute("UPDATE sessions SET total_speech_segments = MAX(0, total_speech_segments - 1) WHERE id = ?", (session_id,))
    seq = record_event(conn, "AUDIO_DELETED", session_id, {"segment_id": segment_id})
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "AUDIO_DELETED",
        "seq": seq,
        "session_id": session_id,
        "segment_id": segment_id
    })
    return {"status": "deleted", "segment_id": segment_id}

@app.get("/api/v1/health")
def health_check():
    start = time.time()
    conn = get_db_conn()
    conn.execute("SELECT 1").fetchone()
    conn.close()
    latency_ms = round((time.time() - start) * 1000, 2)
    return {
        "status": "healthy",
        "db_latency_ms": latency_ms,
        "uptime": time.time(),
        "storage_ready": AUDIO_STORAGE_DIR.exists()
    }

@app.post("/api/v1/test/seed-demo")
async def seed_demo():
    res = seed_demo_data()
    await manager.broadcast({
        "type": "SESSION_START",
        "session_id": res["session_id"],
        "timestamp": time.time()
    })
    return res

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port, reload=False)
