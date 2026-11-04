import os
import io
import wave
import sys
import time
import uuid
import json
import math
import hmac
import base64
import struct
import shutil
import secrets
import sqlite3
import hashlib
from pathlib import Path
from typing import Optional, List, Dict, Any

from fastapi import FastAPI, UploadFile, File, Form, Depends, HTTPException, status, Header, Request, WebSocket, WebSocketDisconnect, Query
from fastapi.responses import JSONResponse, Response, FileResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import jwt

try:
    from argon2 import PasswordHasher
    from argon2.exceptions import VerifyMismatchError, InvalidHashError
    ARGON2_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4, hash_len=32, salt_len=16)
    HAS_ARGON2 = True
except Exception:
    ARGON2_HASHER = None
    HAS_ARGON2 = False

# Environment and Path setup
BASE_DIR = Path(__file__).resolve().parent
STORAGE_DIR = Path(os.environ.get("SCREAM_STORAGE_DIR", str(BASE_DIR / "storage"))).resolve()
AUDIO_STORAGE_DIR = STORAGE_DIR / "audio"
BACKUP_STORAGE_DIR = STORAGE_DIR / "backups"
DB_PATH = STORAGE_DIR / "scream_runner.db"
JWT_SECRET_FILE = STORAGE_DIR / ".jwt_secret"
LEGACY_SECRET_FILE = STORAGE_DIR / ".admin_secret"

os.umask(0o077)
AUDIO_STORAGE_DIR.mkdir(parents=True, exist_ok=True)
BACKUP_STORAGE_DIR.mkdir(parents=True, exist_ok=True)

# Remove legacy plaintext secret file if present
if LEGACY_SECRET_FILE.exists():
    try:
        LEGACY_SECRET_FILE.unlink()
    except Exception:
        pass


def load_or_create_jwt_secret() -> str:
    env_secret = os.environ.get("JWT_SECRET", "").strip()
    if len(env_secret) >= 32:
        return env_secret
    if JWT_SECRET_FILE.exists():
        try:
            saved = JWT_SECRET_FILE.read_text().strip()
            if len(saved) >= 32:
                return saved
        except Exception:
            pass
    generated = secrets.token_hex(32)
    try:
        JWT_SECRET_FILE.write_text(generated)
        os.chmod(JWT_SECRET_FILE, 0o600)
    except Exception:
        pass
    return generated


JWT_SECRET = load_or_create_jwt_secret()
JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL_SEC = 3600       # 1 hour short-lived access token
REFRESH_TOKEN_TTL_SEC = 604800    # 7 days refresh token
MAX_UPLOAD_BYTES = 25 * 1024 * 1024  # 25 MB upload limit

# Rate limiting buckets
failed_login_attempts: Dict[str, List[float]] = {}
RATE_LIMIT_WINDOW_SEC = 60
MAX_FAILED_ATTEMPTS = 5

# Global monotonic event sequence counter
event_seq_counter = 0


def hash_password(password: str) -> str:
    """Hashes a password using Argon2id (or PBKDF2-HMAC-SHA256 600k fallback)."""
    if HAS_ARGON2 and ARGON2_HASHER is not None:
        return ARGON2_HASHER.hash(password)
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600000, dklen=32)
    return f"pbkdf2_sha256$600000${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored_hash: str) -> bool:
    """Verifies a password against an Argon2id or PBKDF2-HMAC-SHA256 hash in constant time."""
    if not stored_hash or not password:
        return False
    if stored_hash.startswith("$argon2"):
        if not HAS_ARGON2 or ARGON2_HASHER is None:
            return False
        try:
            return ARGON2_HASHER.verify(stored_hash, password)
        except (VerifyMismatchError, InvalidHashError, Exception):
            return False
    if stored_hash.startswith("pbkdf2_sha256$"):
        try:
            parts = stored_hash.split("$")
            iterations = int(parts[1])
            salt = bytes.fromhex(parts[2])
            expected_dk = bytes.fromhex(parts[3])
            computed_dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, dklen=len(expected_dk))
            return hmac.compare_digest(computed_dk, expected_dk)
        except Exception:
            return False
    return False


def get_db_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=15.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    conn.execute("PRAGMA foreign_keys = ON;")
    return conn


def init_db():
    global event_seq_counter
    conn = get_db_conn()
    with conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            description TEXT NOT NULL,
            applied_at REAL NOT NULL
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS admin_users (
            username TEXT PRIMARY KEY,
            password_hash TEXT NOT NULL,
            recovery_key_hash TEXT NOT NULL,
            hash_algorithm TEXT NOT NULL,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        );
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            device_token TEXT NOT NULL UNIQUE,
            os_version TEXT DEFAULT 'Android',
            app_version TEXT DEFAULT '3.0.0',
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

        segment_cols = [r["name"] for r in conn.execute("PRAGMA table_info(audio_segments)").fetchall()]
        if "recording_kind" not in segment_cols:
            conn.execute("ALTER TABLE audio_segments ADD COLUMN recording_kind TEXT NOT NULL DEFAULT 'speech'")

        # Apply v3 migration columns safely if upgrading from v2.x database
        existing_cols = [r["name"] for r in conn.execute("PRAGMA table_info(devices)").fetchall()]
        if "os_version" not in existing_cols:
            conn.execute("ALTER TABLE devices ADD COLUMN os_version TEXT DEFAULT 'Android'")
        if "app_version" not in existing_cols:
            conn.execute("ALTER TABLE devices ADD COLUMN app_version TEXT DEFAULT '3.0.0'")

        conn.execute(
            "INSERT OR IGNORE INTO schema_migrations (version, description, applied_at) VALUES (3, 'v3.0 Argon2id admin_users, device tokens, migrations & backups', ?)",
            (time.time(),)
        )

        # Purge any legacy synthetic/demo data
        conn.execute("DELETE FROM audio_segments WHERE session_id = 'sess_demo_run_01'")
        conn.execute("DELETE FROM sessions WHERE id = 'sess_demo_run_01'")
        conn.execute("DELETE FROM devices WHERE id = 'dev_cyber_pixel_7'")

        max_seq_row = conn.execute("SELECT COALESCE(MAX(event_seq), 0) FROM events").fetchone()
        event_seq_counter = int(max_seq_row[0]) if max_seq_row else 0

        # Optional environment-driven bootstrap if deployment owner explicitly provides ADMIN_INITIAL_PASSWORD
        env_init_pass = os.environ.get("ADMIN_INITIAL_PASSWORD", "").strip()
        if env_init_pass and len(env_init_pass) >= 8:
            existing_admin = conn.execute("SELECT username FROM admin_users LIMIT 1").fetchone()
            if not existing_admin:
                env_user = os.environ.get("ADMIN_USERNAME", "admin").strip() or "admin"
                env_recovery = os.environ.get("ADMIN_RECOVERY_KEY", "").strip() or f"SR3-{secrets.token_hex(8).upper()}"
                now = time.time()
                conn.execute(
                    "INSERT INTO admin_users (username, password_hash, recovery_key_hash, hash_algorithm, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        env_user,
                        hash_password(env_init_pass),
                        hash_password(env_recovery),
                        "argon2id" if HAS_ARGON2 else "pbkdf2_sha256",
                        now,
                        now,
                    )
                )
    conn.close()


init_db()

app = FastAPI(title="Scream Runner & Sam's Dashboard Backend", version="3.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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


# ----------------- JWT Token Creation & Validation -----------------
def create_jwt_token(subject: str, role: str = "admin", token_type: str = "access", expires_in_seconds: int = ACCESS_TOKEN_TTL_SEC) -> str:
    jti = str(uuid.uuid4())
    now = int(time.time())
    payload = {
        "sub": subject,
        "role": role,
        "type": token_type,
        "jti": jti,
        "iat": now,
        "exp": now + expires_in_seconds
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_and_validate_jwt(raw_token: str, expected_type: str = "access", expected_role: str = "admin") -> dict:
    try:
        payload = jwt.decode(raw_token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token has expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token signature")

    if payload.get("type") != expected_type:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid token type: expected '{expected_type}'"
        )
    if expected_role and payload.get("role") != expected_role:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Insufficient role permissions"
        )

    jti = payload.get("jti")
    conn = get_db_conn()
    revoked = conn.execute("SELECT 1 FROM revoked_tokens WHERE jti = ?", (jti,)).fetchone()
    conn.close()
    if revoked:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token has been revoked")
    return payload


def verify_admin_token(
    authorization: Optional[str] = Header(None),
    token: Optional[str] = Query(None)
) -> dict:
    raw = None
    if authorization and authorization.startswith("Bearer "):
        raw = authorization[7:].strip()
    elif token:
        raw = token.strip()
    if not raw or raw in ("null", "undefined"):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing or invalid administrator Authorization token")
    return decode_and_validate_jwt(raw, expected_type="access", expected_role="admin")


def verify_device_or_admin(
    x_device_token: Optional[str] = Header(None),
    authorization: Optional[str] = Header(None)
) -> dict:
    """Validates either an enrolled device token (X-Device-Token) or an administrator Bearer token."""
    if x_device_token and x_device_token.strip():
        tok = x_device_token.strip()
        conn = get_db_conn()
        dev = conn.execute("SELECT id, name FROM devices WHERE device_token = ?", (tok,)).fetchone()
        conn.close()
        if dev:
            return {"actor": "device", "device_id": dev["id"], "device_name": dev["name"]}
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or unrecognized X-Device-Token")

    if authorization and authorization.startswith("Bearer "):
        raw = authorization[7:].strip()
        if raw.startswith("dev_tok_"):
            conn = get_db_conn()
            dev = conn.execute("SELECT id, name FROM devices WHERE device_token = ?", (raw,)).fetchone()
            conn.close()
            if dev:
                return {"actor": "device", "device_id": dev["id"], "device_name": dev["name"]}
        payload = decode_and_validate_jwt(raw, expected_type="access", expected_role="admin")
        return {"actor": "admin", "username": payload.get("sub")}

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Device authentication required (provide X-Device-Token from /api/v1/devices/register)"
    )


def check_rate_limit(client_ip: str):
    now = time.time()
    attempts = [t for t in failed_login_attempts.get(client_ip, []) if now - t < RATE_LIMIT_WINDOW_SEC]
    failed_login_attempts[client_ip] = attempts
    if len(attempts) >= MAX_FAILED_ATTEMPTS:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many failed authentication attempts. Please wait 60 seconds before retrying."
        )


def record_failed_attempt(client_ip: str):
    failed_login_attempts.setdefault(client_ip, []).append(time.time())


# ----------------- Pydantic Models -----------------
class AdminSetupRequest(BaseModel):
    username: str = Field(default="admin", min_length=3, max_length=64)
    password: str = Field(..., min_length=8, max_length=128)


class LoginRequest(BaseModel):
    username: str
    password: str


class PasswordResetRequest(BaseModel):
    username: str = "admin"
    recovery_key: str
    new_password: str = Field(..., min_length=8, max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str


class DeviceRegisterRequest(BaseModel):
    pairing_code: str = ""
    id: Optional[str] = None
    name: str
    os_version: Optional[str] = "Android 14"
    app_version: Optional[str] = "3.0.0"


class SessionCreateRequest(BaseModel):
    session_id: Optional[str] = None
    device_id: str
    device_name: Optional[str] = None
    consent_confirmed: bool = False


class SessionStopRequest(BaseModel):
    session_id: str


class SessionInterruptionRequest(BaseModel):
    session_id: str
    event_type: str = "INTERRUPTION"
    reason: str = "Microphone or network interruption"
    duration_ms: int = 0


class StorageRestoreRequest(BaseModel):
    devices: Optional[List[Dict[str, Any]]] = None
    sessions: Optional[List[Dict[str, Any]]] = None
    audio_segments: Optional[List[Dict[str, Any]]] = None


# ----------------- Administrator Setup, Login & Reset Endpoints -----------------
@app.get("/api/v1/auth/status")
def auth_setup_status():
    conn = get_db_conn()
    row = conn.execute("SELECT username, hash_algorithm, created_at, updated_at FROM admin_users LIMIT 1").fetchone()
    conn.close()
    if row:
        return {
            "setup_required": False,
            "admin_username": row["username"],
            "hash_algorithm": row["hash_algorithm"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "access_token_ttl_seconds": ACCESS_TOKEN_TTL_SEC,
            "refresh_token_ttl_seconds": REFRESH_TOKEN_TTL_SEC,
        }
    return {
        "setup_required": True,
        "admin_username": None,
        "hash_algorithm": "argon2id" if HAS_ARGON2 else "pbkdf2_sha256",
        "message": "First-time administrator setup required. Call POST /api/v1/auth/setup to configure your administrator password."
    }


@app.post("/api/v1/auth/setup")
def setup_first_admin(req: AdminSetupRequest, request: Request, x_setup_token: Optional[str] = Header(None)):
    expected = os.environ.get("ADMIN_SETUP_TOKEN", "")
    if len(expected) < 32 or not x_setup_token or not hmac.compare_digest(expected, x_setup_token):
        raise HTTPException(status_code=403, detail="Private deployment setup token required")
    username = req.username.strip()
    password = req.password
    if len(username) < 3:
        raise HTTPException(status_code=400, detail="Administrator username must be at least 3 characters")
    if len(password) < 8:
        raise HTTPException(status_code=400, detail="Administrator password must be at least 8 characters")

    conn = get_db_conn()
    conn.execute("BEGIN IMMEDIATE")
    existing = conn.execute("SELECT username FROM admin_users LIMIT 1").fetchone()
    if existing:
        conn.close()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Administrator account is already configured. Sign in with your password or use your deployment Recovery Key at POST /api/v1/auth/reset-password."
        )

    recovery_key = f"SR3-{secrets.token_hex(4).upper()}-{secrets.token_hex(4).upper()}-{secrets.token_hex(4).upper()}"
    now = time.time()
    pw_hash = hash_password(password)
    rec_hash = hash_password(recovery_key)
    algo = "argon2id" if HAS_ARGON2 else "pbkdf2_sha256"

    with conn:
        conn.execute(
            "INSERT INTO admin_users (username, password_hash, recovery_key_hash, hash_algorithm, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            (username, pw_hash, rec_hash, algo, now, now)
        )
        record_event(conn, "ADMIN_SETUP_COMPLETED", None, {"username": username, "hash_algorithm": algo})
    conn.close()

    access_token = create_jwt_token(username, role="admin", token_type="access", expires_in_seconds=ACCESS_TOKEN_TTL_SEC)
    refresh_token = create_jwt_token(username, role="admin", token_type="refresh", expires_in_seconds=REFRESH_TOKEN_TTL_SEC)

    return {
        "status": "setup_completed",
        "username": username,
        "hash_algorithm": algo,
        "recovery_key": recovery_key,
        "recovery_notice": "Save this Recovery Key in a secure password manager. It is shown only once and is required to reset your password.",
        "access_token": access_token,
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "expires_in": ACCESS_TOKEN_TTL_SEC,
        "user": {"username": username, "role": "admin"}
    }


@app.post("/api/v1/auth/login")
def login(req: LoginRequest, request: Request):
    client_ip = request.client.host if request.client else "unknown"
    check_rate_limit(client_ip)

    conn = get_db_conn()
    admin_row = conn.execute("SELECT username, password_hash FROM admin_users WHERE username = ?", (req.username.strip(),)).fetchone()
    any_admin = conn.execute("SELECT username FROM admin_users LIMIT 1").fetchone()
    conn.close()

    if not any_admin:
        raise HTTPException(
            status_code=status.HTTP_428_PRECONDITION_REQUIRED,
            detail="First-time administrator setup is required before signing in. Please create your administrator account first."
        )

    if admin_row and verify_password(req.password, admin_row["password_hash"]):
        failed_login_attempts.pop(client_ip, None)
        access_token = create_jwt_token(admin_row["username"], role="admin", token_type="access", expires_in_seconds=ACCESS_TOKEN_TTL_SEC)
        refresh_token = create_jwt_token(admin_row["username"], role="admin", token_type="refresh", expires_in_seconds=REFRESH_TOKEN_TTL_SEC)
        conn2 = get_db_conn()
        record_event(conn2, "ADMIN_LOGIN_SUCCESS", None, {"username": admin_row["username"], "ip": client_ip})
        conn2.close()
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "bearer",
            "expires_in": ACCESS_TOKEN_TTL_SEC,
            "user": {"username": admin_row["username"], "role": "admin"}
        }

    record_failed_attempt(client_ip)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid administrator username or password")


@app.post("/api/v1/auth/reset-password")
def reset_admin_password(req: PasswordResetRequest, request: Request):
    client_ip = request.client.host if request.client else "unknown"
    check_rate_limit(client_ip)

    if len(req.new_password) < 8:
        raise HTTPException(status_code=400, detail="New password must be at least 8 characters")

    conn = get_db_conn()
    admin_row = conn.execute(
        "SELECT username, recovery_key_hash FROM admin_users WHERE username = ?",
        (req.username.strip(),)
    ).fetchone()

    if not admin_row:
        conn.close()
        record_failed_attempt(client_ip)
        raise HTTPException(status_code=404, detail="Administrator account not found")

    env_master_recovery = os.environ.get("ADMIN_RECOVERY_KEY", "").strip()
    valid_recovery = verify_password(req.recovery_key.strip(), admin_row["recovery_key_hash"]) or (
        bool(env_master_recovery) and hmac.compare_digest(req.recovery_key.strip(), env_master_recovery)
    )

    if not valid_recovery:
        conn.close()
        record_failed_attempt(client_ip)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid deployment Recovery Key")

    failed_login_attempts.pop(client_ip, None)
    new_pw_hash = hash_password(req.new_password)
    algo = "argon2id" if HAS_ARGON2 else "pbkdf2_sha256"
    now = time.time()

    with conn:
        conn.execute(
            "UPDATE admin_users SET password_hash = ?, hash_algorithm = ?, updated_at = ? WHERE username = ?",
            (new_pw_hash, algo, now, admin_row["username"])
        )
        record_event(conn, "ADMIN_PASSWORD_RESET", None, {"username": admin_row["username"], "ip": client_ip})
    conn.close()

    access_token = create_jwt_token(admin_row["username"], role="admin", token_type="access", expires_in_seconds=ACCESS_TOKEN_TTL_SEC)
    refresh_token = create_jwt_token(admin_row["username"], role="admin", token_type="refresh", expires_in_seconds=REFRESH_TOKEN_TTL_SEC)
    return {
        "status": "password_reset",
        "username": admin_row["username"],
        "access_token": access_token,
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "expires_in": ACCESS_TOKEN_TTL_SEC
    }


@app.post("/api/v1/auth/refresh")
def refresh_token_endpoint(req: RefreshRequest):
    payload = decode_and_validate_jwt(req.refresh_token.strip(), expected_type="refresh", expected_role="admin")
    username = payload.get("sub", "admin")
    old_jti = payload.get("jti")
    if old_jti:
        conn = get_db_conn()
        conn.execute("INSERT OR IGNORE INTO revoked_tokens (jti, revoked_at) VALUES (?, ?)", (old_jti, time.time()))
        conn.commit()
        conn.close()

    new_access_token = create_jwt_token(username, role="admin", token_type="access", expires_in_seconds=ACCESS_TOKEN_TTL_SEC)
    new_refresh_token = create_jwt_token(username, role="admin", token_type="refresh", expires_in_seconds=REFRESH_TOKEN_TTL_SEC)
    return {
        "access_token": new_access_token,
        "refresh_token": new_refresh_token,
        "token_type": "bearer",
        "expires_in": ACCESS_TOKEN_TTL_SEC
    }


@app.get("/api/v1/auth/me")
def get_current_user(payload: dict = Depends(verify_admin_token)):
    return {
        "authenticated": True,
        "username": payload.get("sub"),
        "role": payload.get("role"),
        "token_type": payload.get("type"),
        "jti": payload.get("jti")
    }


@app.get("/api/v1/auth/credentials-info")
def credentials_info():
    conn = get_db_conn()
    row = conn.execute("SELECT username, hash_algorithm, created_at FROM admin_users LIMIT 1").fetchone()
    conn.close()
    return {
        "setup_required": row is None,
        "admin_username": row["username"] if row else None,
        "hash_algorithm": row["hash_algorithm"] if row else ("argon2id" if HAS_ARGON2 else "pbkdf2_sha256"),
        "auth_required": True,
        "default_passwords_disabled": True,
        "note": "Default passwords and hardcoded credentials are completely removed in v3.0. First-time setup hashes administrator credentials with Argon2id."
    }


@app.post("/api/v1/auth/logout")
def logout(authorization: Optional[str] = Header(None), req_body: Optional[Dict[str, str]] = None):
    revoked_count = 0
    tokens_to_revoke = []
    if authorization and authorization.startswith("Bearer "):
        tokens_to_revoke.append(authorization[7:].strip())
    if req_body and isinstance(req_body, dict) and req_body.get("refresh_token"):
        tokens_to_revoke.append(req_body["refresh_token"].strip())

    conn = get_db_conn()
    for tok in tokens_to_revoke:
        try:
            payload = jwt.decode(tok, JWT_SECRET, algorithms=[JWT_ALGORITHM], options={"verify_exp": False})
            jti = payload.get("jti")
            if jti:
                conn.execute("INSERT OR IGNORE INTO revoked_tokens (jti, revoked_at) VALUES (?, ?)", (jti, time.time()))
                revoked_count += 1
        except Exception:
            pass
    conn.commit()
    conn.close()
    return {"status": "ok", "revoked_tokens": revoked_count, "message": "Logged out and token(s) revoked"}


# ----------------- APK Downloads -----------------
PUBLIC_DOWNLOADS_DIR = BASE_DIR.parent / "public" / "downloads"


@app.get("/api/v1/downloads/info")
def get_downloads_info():
    apk_files = [
        "scream-runner-v3.0.apk",
        "sams-dashboard-v3.0.apk",
        "scream-runner.apk",
        "sams-dashboard.apk",
    ]
    info = []
    seen = set()
    for fname in apk_files:
        fpath = PUBLIC_DOWNLOADS_DIR / fname
        if not fpath.exists():
            if fname.startswith("scream-runner"):
                fpath = PUBLIC_DOWNLOADS_DIR / "scream-runner.apk"
            elif fname.startswith("sams-dashboard"):
                fpath = PUBLIC_DOWNLOADS_DIR / "sams-dashboard.apk"
        if fpath.exists() and fname not in seen:
            seen.add(fname)
            data = fpath.read_bytes()
            info.append({
                "filename": fname,
                "size_bytes": len(data),
                "size_kb": round(len(data) / 1024, 1),
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


# ----------------- Authenticated Device Enrollment & Management -----------------
@app.get("/api/v1/devices/pairing-code")
def get_pairing_code(auth: dict = Depends(verify_admin_token)):
    # Generated by the deployment owner, never embedded in either APK.
    code = os.environ.get("DEVICE_PAIRING_CODE", "")
    if len(code) < 16:
        raise HTTPException(status_code=503, detail="Configure DEVICE_PAIRING_CODE (at least 16 random characters) privately on the backend")
    return {"pairing_code": code}


@app.post("/api/v1/devices/register")
async def register_device(req: DeviceRegisterRequest, request: Request):
    check_rate_limit(request.client.host if request.client else "unknown")
    code = os.environ.get("DEVICE_PAIRING_CODE", "")
    if len(code) < 16 or not hmac.compare_digest(req.pairing_code, code):
        record_failed_attempt(request.client.host if request.client else "unknown")
        raise HTTPException(status_code=403, detail="Valid private pairing code required")
    name = req.name.strip()
    if not name or len(name) > 120:
        raise HTTPException(status_code=400, detail="Valid device name is required")
    # Never disclose another device's token based on a public name or identifier.
    device_id = "dev_" + uuid.uuid4().hex
    token = "dev_tok_" + secrets.token_hex(32)
    now = time.time()
    with get_db_conn() as conn:
        conn.execute(
            "INSERT INTO devices (id, name, device_token, os_version, app_version, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (device_id, name, token, req.os_version, req.app_version, now, now, "online"))
        record_event(conn, "DEVICE_REGISTERED", None, {"device_id": device_id, "name": name})
    conn.close()
    return {"device_id": device_id, "device_token": token, "status": "registered"}


def require_device_owner(actor: dict, device_id: str):
    if actor.get("actor") == "device" and actor.get("device_id") != device_id:
        raise HTTPException(status_code=403, detail="Device does not own this resource")


def require_session_owner(conn, actor, session_id):
    row = conn.execute("SELECT device_id FROM sessions WHERE id = ?", (session_id,)).fetchone()
    if row:
        try:
            require_device_owner(actor, row["device_id"])
        except HTTPException:
            conn.close()
            raise
    return row


@app.post("/api/v1/devices/{device_id}/ping")
async def ping_device(
    device_id: str,
    status_val: str = "online",
    name: Optional[str] = None,
    actor: dict = Depends(verify_device_or_admin)
):
    require_device_owner(actor, device_id)
    conn = get_db_conn()
    now = time.time()
    existing = conn.execute("SELECT id FROM devices WHERE id = ?", (device_id,)).fetchone()
    if existing:
        conn.execute("UPDATE devices SET last_ping = ?, status = ? WHERE id = ?", (now, status_val, device_id))
    else:
        dev_name = name or actor.get("device_name") or f"Android Device ({device_id[:8]})"
        token = f"dev_tok_{secrets.token_hex(24)}"
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
def list_devices(auth: dict = Depends(verify_admin_token)):
    conn = get_db_conn()
    rows = conn.execute(
        "SELECT id, name, os_version, app_version, registered_at, last_ping, status FROM devices ORDER BY last_ping DESC"
    ).fetchall()
    devices = [dict(r) for r in rows]
    conn.close()
    return {"devices": devices}


# ----------------- Sessions -----------------
@app.post("/api/v1/sessions/start")
async def start_session(req: SessionCreateRequest, actor: dict = Depends(verify_device_or_admin)):
    require_device_owner(actor, req.device_id)
    if not req.consent_confirmed:
        raise HTTPException(status_code=400, detail="Explicit user microphone consent is required to start a session")

    conn = get_db_conn()
    now = time.time()

    dev_row = conn.execute("SELECT id, name FROM devices WHERE id = ?", (req.device_id,)).fetchone()
    dev_name = req.device_name or (dev_row["name"] if dev_row else actor.get("device_name", f"Android Device ({req.device_id[:10]})"))
    if not dev_row:
        tok = f"dev_tok_{secrets.token_hex(24)}"
        conn.execute(
            "INSERT OR IGNORE INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, 'recording')",
            (req.device_id, dev_name, tok, now, now)
        )
    else:
        conn.execute("UPDATE devices SET last_ping = ?, status = 'recording' WHERE id = ?", (now, req.device_id))

    session_id = req.session_id or f"sess_{uuid.uuid4().hex[:10]}"
    require_session_owner(conn, actor, session_id)
    conn.execute(
        """INSERT OR IGNORE INTO sessions (id, device_id, started_at, consent_confirmed, status, total_speech_segments)
           VALUES (?, ?, ?, 1, 'active', COALESCE((SELECT total_speech_segments FROM sessions WHERE id = ?), 0))""",
        (session_id, req.device_id, now, session_id)
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
async def stop_session(req: SessionStopRequest, actor: dict = Depends(verify_device_or_admin)):
    conn = get_db_conn()
    require_session_owner(conn, actor, req.session_id)
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


@app.post("/api/v1/sessions/interruption")
async def log_session_interruption(req: SessionInterruptionRequest, actor: dict = Depends(verify_device_or_admin)):
    conn = get_db_conn()
    if not require_session_owner(conn, actor, req.session_id):
        conn.close()
        raise HTTPException(status_code=404, detail="Session not found")
    ev_type = req.event_type if req.event_type in ("INTERRUPTION", "NETWORK_LOSS") else "INTERRUPTION"
    seq = record_event(conn, ev_type, req.session_id, {
        "reason": req.reason,
        "duration_ms": req.duration_ms,
        "timestamp": time.time()
    })
    conn.commit()
    conn.close()
    await manager.broadcast({
        "type": ev_type,
        "seq": seq,
        "session_id": req.session_id,
        "reason": req.reason,
        "duration_ms": req.duration_ms
    })
    return {"status": "logged", "event_seq": seq, "session_id": req.session_id}


@app.get("/api/v1/sessions")
def list_sessions(auth: dict = Depends(verify_admin_token)):
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
    try:
        with wave.open(io.BytesIO(contents), "rb") as audio:
            if audio.getsampwidth() != 2 or audio.getnchannels() != 1:
                return "[]"
            pcm = audio.readframes(audio.getnframes())
    except (wave.Error, EOFError):
        return "[]"
    total_samples = len(pcm) // 2
    if total_samples <= 0:
        return "[]"
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
        bars.append(round(min(1.0, max(0.0, rms / 32768.0)), 3))
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
    speech_confidence: float = Form(0.0),
    sharing_authorized: bool = Form(False),
    recording_kind: str = Form("speech"),
    file_hash: Optional[str] = Form(None),
    waveform_json: Optional[str] = Form(None),
    device_id: Optional[str] = Form(None),
    device_name: Optional[str] = Form(None),
    actor: dict = Depends(verify_device_or_admin)
):
    if recording_kind not in ("full", "speech"):
        raise HTTPException(status_code=400, detail="Invalid recording kind")
    if not sharing_authorized:
        raise HTTPException(status_code=403, detail="Review and explicit sharing authorization are required")
    resolved_device_id = device_id or actor.get("device_id")
    if not resolved_device_id:
        raise HTTPException(status_code=400, detail="A paired device is required")
    require_device_owner(actor, resolved_device_id)
    if (not all(math.isfinite(v) for v in (start_time, end_time, peak_db, avg_db, speech_confidence))
            or duration_ms <= 0 or end_time < start_time or seq_index < 1
            or not -120 <= peak_db <= 0 or not -120 <= avg_db <= 0
            or not 0 <= speech_confidence <= 1):
        raise HTTPException(status_code=400, detail="Invalid measured audio metadata")
    contents = await audio_file.read(MAX_UPLOAD_BYTES + 1)
    if len(contents) == 0:
        raise HTTPException(status_code=400, detail="Uploaded audio file is empty")
    if len(contents) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Uploaded audio file exceeds 25 MB limit")

    computed_hash = hashlib.sha256(contents).hexdigest()
    if file_hash and file_hash.strip().lower() != computed_hash.lower():
        raise HTTPException(status_code=400, detail="SHA-256 integrity verification failed")

    final_hash = computed_hash
    safe_session_id = "".join(c for c in session_id if c.isalnum() or c in ("_", "-"))[:64]
    if not safe_session_id:
        raise HTTPException(status_code=400, detail="Invalid session_id")

    conn = get_db_conn()
    require_session_owner(conn, actor, safe_session_id)
    existing = conn.execute(
        "SELECT id, file_path FROM audio_segments WHERE session_id = ? AND file_hash = ?",
        (safe_session_id, final_hash)
    ).fetchone()

    if existing:
        conn.close()
        return {
            "status": "already_exists",
            "segment_id": existing["id"],
            "session_id": safe_session_id,
            "file_hash": final_hash,
            "message": "Segment already ingested with identical SHA-256 hash (idempotent)."
        }

    try:
        waveform = json.loads(waveform_json) if waveform_json else json.loads(compute_waveform_from_wav_bytes(contents))
        if not isinstance(waveform, list) or len(waveform) > 10000 or any(
                isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 1 for v in waveform):
            raise ValueError()
        final_waveform = json.dumps(waveform, allow_nan=False)
    except (ValueError, TypeError):
        conn.close()
        raise HTTPException(status_code=400, detail="Invalid measured waveform")
    segment_id = str(uuid.uuid4())
    session_audio_dir = AUDIO_STORAGE_DIR / safe_session_id
    session_audio_dir.mkdir(parents=True, exist_ok=True)

    raw_ext = Path(audio_file.filename).suffix.lower() if audio_file.filename else ".wav"
    ext = raw_ext if raw_ext in (".wav", ".m4a", ".aac") else ".wav"
    dest_path = session_audio_dir / f"{segment_id}{ext}"
    with open(dest_path, "wb") as f:
        f.write(contents)

    now = time.time()
    resolved_device_id = device_id or actor.get("device_id") or "dev_enrolled"
    resolved_device_name = device_name or actor.get("device_name") or "Scream Runner Client"

    dev_exists = conn.execute("SELECT id FROM devices WHERE id = ?", (resolved_device_id,)).fetchone()
    if not dev_exists:
        tok = f"dev_tok_{secrets.token_hex(24)}"
        conn.execute(
            "INSERT OR IGNORE INTO devices (id, name, device_token, registered_at, last_ping, status) VALUES (?, ?, ?, ?, ?, 'recording')",
            (resolved_device_id, resolved_device_name, tok, now, now)
        )
    else:
        conn.execute("UPDATE devices SET last_ping = ?, status = 'recording' WHERE id = ?", (now, resolved_device_id))

    conn.execute("""
        INSERT OR IGNORE INTO sessions (id, device_id, started_at, consent_confirmed, status)
        VALUES (?, ?, ?, 1, 'active')
    """, (safe_session_id, resolved_device_id, now))


    conn.execute("""
        INSERT INTO audio_segments (
            id, session_id, seq_index, start_time, end_time, duration_ms,
            peak_db, avg_db, speech_confidence, file_path, file_hash, file_size_bytes,
            created_at, waveform_data
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        segment_id, safe_session_id, seq_index, start_time, end_time, duration_ms,
        peak_db, avg_db, speech_confidence, str(dest_path), final_hash, len(contents),
        now, final_waveform
    ))
    conn.execute("UPDATE audio_segments SET recording_kind = ? WHERE id = ?", (recording_kind, segment_id))
    conn.execute("UPDATE sessions SET total_speech_segments = total_speech_segments + 1 WHERE id = ?", (safe_session_id,))

    is_highlight = peak_db > -10.0
    seq = record_event(conn, "AUDIO_UPLOADED", safe_session_id, {
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
        "session_id": safe_session_id,
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
        "session_id": safe_session_id,
        "file_hash": final_hash,
        "is_highlight": is_highlight
    }


@app.get("/api/v1/sessions/{session_id}/segments")
def get_session_segments(session_id: str, auth: dict = Depends(verify_admin_token)):
    conn = get_db_conn()
    rows = conn.execute("""
        SELECT id, session_id, seq_index, start_time, end_time, duration_ms,
               peak_db, avg_db, speech_confidence, file_hash, file_size_bytes, created_at, waveform_data, recording_kind
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


# ----------------- Protected HTTP 206 Byte-Range Audio Streaming & Download -----------------
@app.get("/api/v1/audio/{segment_id}/stream")
def stream_audio(segment_id: str, request: Request, auth: dict = Depends(verify_admin_token)):
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
def download_audio(segment_id: str, auth: dict = Depends(verify_admin_token)):
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


# ----------------- Missed Events & Protected WebSocket -----------------
@app.get("/api/v1/events/missed")
def get_missed_events(since_seq: int = 0, session_id: Optional[str] = None, auth: dict = Depends(verify_admin_token)):
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
async def websocket_endpoint(websocket: WebSocket, token: Optional[str] = Query(None)):
    # Validate administrator token if provided or required
    auth_header = websocket.headers.get("authorization")
    raw_token = None
    if auth_header and auth_header.startswith("Bearer "):
        raw_token = auth_header[7:].strip()
    elif token:
        raw_token = token.strip()

    if not raw_token:
        await websocket.close(code=1008, reason="Administrator authentication token required")
        return

    try:
        decode_and_validate_jwt(raw_token, expected_type="access", expected_role="admin")
    except HTTPException:
        await websocket.close(code=1008, reason="Invalid or expired administrator token")
        return

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
                    await websocket.send_text(json.dumps({"type": "PONG", "latest_seq": event_seq_counter, "timestamp": time.time()}))
            except Exception:
                pass
    except WebSocketDisconnect:
        manager.disconnect(websocket)


# ----------------- Storage Management, Backups, Migrations & Health -----------------
@app.get("/api/v1/storage/stats")
def storage_stats(auth: dict = Depends(verify_admin_token)):
    conn = get_db_conn()
    seg_stats = conn.execute("SELECT COUNT(*), COALESCE(SUM(file_size_bytes), 0) FROM audio_segments").fetchone()
    session_count = conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    active_sessions = conn.execute("SELECT COUNT(*) FROM sessions WHERE status = 'active'").fetchone()[0]
    device_count = conn.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
    migrations = [dict(r) for r in conn.execute("SELECT version, description, applied_at FROM schema_migrations ORDER BY version ASC").fetchall()]
    conn.close()

    total_segments = seg_stats[0]
    total_bytes = seg_stats[1]

    return {
        "total_segments": total_segments,
        "total_bytes": total_bytes,
        "total_megabytes": round(total_bytes / (1024 * 1024), 2),
        "total_sessions": session_count,
        "active_sessions": active_sessions,
        "total_devices": device_count,
        "schema_migrations": migrations,
        "storage_mode": "persistent_volume" if os.environ.get("PERSISTENT_VOLUME_MOUNTED") == "true" else "local_sqlite_wal_with_portable_snapshots"
    }


@app.get("/api/v1/storage/backup")
def export_portable_backup(include_audio_base64: bool = True, auth: dict = Depends(verify_admin_token)):
    """Exports a complete, verifiable snapshot of devices, sessions, audio segments, and audio bytes."""
    conn = get_db_conn()
    devices = [dict(r) for r in conn.execute("SELECT * FROM devices").fetchall()]
    sessions = [dict(r) for r in conn.execute("SELECT * FROM sessions").fetchall()]
    segments = [dict(r) for r in conn.execute("SELECT * FROM audio_segments").fetchall()]
    conn.close()

    if include_audio_base64:
        for seg in segments:
            fpath = seg.get("file_path")
            if fpath and os.path.exists(fpath):
                try:
                    raw = Path(fpath).read_bytes()
                    seg["audio_base64"] = base64.b64encode(raw).decode("ascii")
                    seg["audio_ext"] = Path(fpath).suffix or ".wav"
                except Exception:
                    seg["audio_base64"] = None

    snapshot = {
        "version": "3.0.0",
        "exported_at": time.time(),
        "devices": devices,
        "sessions": sessions,
        "audio_segments": segments
    }
    snap_path = BACKUP_STORAGE_DIR / "latest_snapshot.json"
    try:
        snap_path.write_text(json.dumps(snapshot))
    except Exception:
        pass
    return snapshot


@app.post("/api/v1/storage/restore")
def restore_portable_backup(req: StorageRestoreRequest, auth: dict = Depends(verify_admin_token)):
    """Restores devices, sessions, and audio files from a portable backup snapshot."""
    conn = get_db_conn()
    conn.execute("PRAGMA foreign_keys=OFF;")
    restored_devices = 0
    restored_sessions = 0
    restored_segments = 0

    try:
        with conn:
            for d in (req.devices or []):
                conn.execute(
                    """INSERT OR REPLACE INTO devices (id, name, device_token, os_version, app_version, registered_at, last_ping, status)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        d["id"],
                        d["name"],
                        d.get("device_token") or f"dev_tok_{secrets.token_hex(24)}",
                        d.get("os_version", "Android"),
                        d.get("app_version", "3.0.0"),
                        d.get("registered_at", time.time()),
                        d.get("last_ping", time.time()),
                        d.get("status", "online"),
                    )
                )
                restored_devices += 1

            for s in (req.sessions or []):
                conn.execute(
                    """INSERT OR REPLACE INTO sessions (id, device_id, started_at, ended_at, duration_seconds, consent_confirmed, total_speech_segments, status)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        s["id"],
                        s["device_id"],
                        s["started_at"],
                        s.get("ended_at"),
                        s.get("duration_seconds", 0.0),
                        s.get("consent_confirmed", 1),
                        s.get("total_speech_segments", 0),
                        s.get("status", "completed"),
                    )
                )
                restored_sessions += 1

            for seg in (req.audio_segments or []):
                sess_dir = AUDIO_STORAGE_DIR / seg["session_id"]
                sess_dir.mkdir(parents=True, exist_ok=True)
                ext = seg.get("audio_ext", ".wav")
                dest_path = sess_dir / f"{seg['id']}{ext}"
                if seg.get("audio_base64"):
                    raw_bytes = base64.b64decode(seg["audio_base64"])
                    dest_path.write_bytes(raw_bytes)
                conn.execute(
                    """INSERT OR REPLACE INTO audio_segments (
                        id, session_id, seq_index, start_time, end_time, duration_ms,
                        peak_db, avg_db, speech_confidence, file_path, file_hash, file_size_bytes,
                        created_at, waveform_data
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        seg["id"],
                        seg["session_id"],
                        seg["seq_index"],
                        seg["start_time"],
                        seg["end_time"],
                        seg["duration_ms"],
                        seg["peak_db"],
                        seg["avg_db"],
                        seg.get("speech_confidence", 0.95),
                        str(dest_path),
                        seg["file_hash"],
                        seg["file_size_bytes"],
                        seg["created_at"],
                        seg.get("waveform_data", "[]"),
                    )
                )
                restored_segments += 1

            record_event(conn, "STORAGE_BACKUP_RESTORED", None, {
                "restored_devices": restored_devices,
                "restored_sessions": restored_sessions,
                "restored_segments": restored_segments
            })
    finally:
        conn.execute("PRAGMA foreign_keys=ON;")
        conn.close()
    return {
        "status": "restored",
        "restored_devices": restored_devices,
        "restored_sessions": restored_sessions,
        "restored_segments": restored_segments
    }


@app.delete("/api/v1/audio/{segment_id}")
async def delete_segment(segment_id: str, auth: dict = Depends(verify_admin_token)):
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
    seq = record_event(conn, "AUDIO_DELETED", session_id, {"segment_id": segment_id, "deleted_by": auth.get("sub", "admin")})
    conn.commit()
    conn.close()

    await manager.broadcast({
        "type": "AUDIO_DELETED",
        "seq": seq,
        "session_id": session_id,
        "segment_id": segment_id
    })
    return {"status": "deleted", "segment_id": segment_id}


@app.get("/api/v1/audit/logs")
def get_audit_logs(limit: int = 50, auth: dict = Depends(verify_admin_token)):
    conn = get_db_conn()
    rows = conn.execute(
        "SELECT id, event_seq, session_id, event_type, timestamp, details FROM events ORDER BY event_seq DESC LIMIT ?",
        (min(200, max(1, limit)),)
    ).fetchall()
    conn.close()
    return {"audit_logs": [dict(r) for r in rows]}


@app.post("/api/v1/storage/cleanup")
def cleanup_old_recordings(max_age_days: float = 30.0, auth: dict = Depends(verify_admin_token)):
    cutoff = time.time() - (max_age_days * 86400.0)
    conn = get_db_conn()
    rows = conn.execute("SELECT id, file_path, session_id FROM audio_segments WHERE created_at < ?", (cutoff,)).fetchall()
    removed = 0
    for r in rows:
        if os.path.exists(r["file_path"]):
            try:
                os.remove(r["file_path"])
            except Exception:
                pass
        conn.execute("DELETE FROM audio_segments WHERE id = ?", (r["id"],))
        removed += 1
    conn.commit()
    conn.close()
    return {"status": "cleaned", "removed_segments": removed}


@app.get("/api/v1/health")
def health_check():
    start = time.time()
    conn = get_db_conn()
    conn.execute("SELECT 1").fetchone()
    admin_configured = conn.execute("SELECT 1 FROM admin_users LIMIT 1").fetchone() is not None
    migration_ver = conn.execute("SELECT COALESCE(MAX(version), 0) FROM schema_migrations").fetchone()[0]
    conn.close()
    latency_ms = round((time.time() - start) * 1000, 2)
    return {
        "status": "healthy",
        "version": "3.0.0",
        "db_latency_ms": latency_ms,
        "uptime": time.time(),
        "storage_ready": AUDIO_STORAGE_DIR.exists(),
        "admin_setup_completed": admin_configured,
        "hash_algorithm": "argon2id" if HAS_ARGON2 else "pbkdf2_sha256",
        "schema_version": migration_ver,
        "persistence_disclosure": "SQLite WAL on container disk + portable JSON/Base64 snapshot backup & restore (/api/v1/storage/backup & /api/v1/storage/restore). Note: Unmounted Cloud Run container filesystems reset on new container cold-starts unless backed by an external volume or snapshot restore."
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port, reload=False)
