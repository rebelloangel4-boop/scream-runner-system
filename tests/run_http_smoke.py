"""Run real HTTP checks in a disposable process/database, not ASGI mocks."""
import os
import secrets
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory() as directory:
    env = {**os.environ, 'SCREAM_STORAGE_DIR':directory, 'PORT':'18577',
           'SYNTHETIC_TEST_ONLY':'1', 'ADMIN_USERNAME':'admin',
           'ADMIN_INITIAL_PASSWORD':secrets.token_urlsafe(32),
           'ADMIN_RECOVERY_KEY':secrets.token_urlsafe(32),
           'DEVICE_PAIRING_CODE':secrets.token_urlsafe(32),
           'JWT_SECRET':secrets.token_urlsafe(48)}
    env.pop('TEST_PUBLIC_URL',None)
    p = subprocess.Popen([sys.executable,'-m','scripts.render_test_entrypoint'],env=env,
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    try:
        output,_=p.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        p.terminate()
        output,_=p.communicate(timeout=20)
    lines = [l for l in output.splitlines() if l.startswith('SYNTHETIC_')]
    for line in lines: print(line)
    if not any(l.startswith('SYNTHETIC_HTTP_CHECK ') for l in lines):
        print(output)
        raise SystemExit('Real HTTP integration did not pass')
