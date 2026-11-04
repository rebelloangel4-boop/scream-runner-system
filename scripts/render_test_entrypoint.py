"""Starts the existing FastAPI application and verifies synthetic-only HTTP behavior."""
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.request
from scripts.live_synthetic_check import verify

if os.environ.get('SYNTHETIC_TEST_ONLY') != '1':
    raise SystemExit('This test launcher requires SYNTHETIC_TEST_ONLY=1')
port = os.environ.get('PORT', '10000')
server = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'backend.main:app',
                           '--host', '0.0.0.0', '--port', port, '--workers', '1', '--no-access-log'])

def stop(signum, frame):
    server.terminate()
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
try:
    for _ in range(90):
        if server.poll() is not None: raise RuntimeError('Server exited before readiness')
        try:
            with urllib.request.urlopen('http://127.0.0.1:'+port+'/api/v1/health', timeout=2) as r:
                if r.status == 200: break
        except Exception: time.sleep(1)
    else: raise RuntimeError('Server readiness timed out')
    verify('http://127.0.0.1:'+port, 'loopback')
except Exception as exc:
    # Exception messages originate from fixed check names, not HTTP payloads or secrets.
    print('SYNTHETIC_STARTUP_CHECK_FAILED '+type(exc).__name__, flush=True)
    server.terminate()
    server.wait(timeout=15)
    raise SystemExit(1)


def public_check():
    url = os.environ.get('TEST_PUBLIC_URL', '')
    if not url: return
    for attempt in range(12):
        time.sleep(15)
        try:
            verify(url, 'public_https')
            return
        except Exception as exc:
            print('SYNTHETIC_PUBLIC_RETRY '+str(attempt+1)+' '+type(exc).__name__, flush=True)
    print('SYNTHETIC_PUBLIC_CHECK_FAILED', flush=True)
threading.Thread(target=public_check, daemon=True).start()
raise SystemExit(server.wait())
