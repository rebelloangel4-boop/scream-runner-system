"""Real HTTP checks using deployment secrets in environment; never logs credentials."""
import hashlib
import json
import math
import os
import urllib.error
import urllib.request
import uuid
from backend.synthetic_fixture import synthetic_wav, synthetic_metrics


def verify(base_url, label='loopback'):
    def request(path, method='GET', body=None, headers=None):
        req = urllib.request.Request(base_url.rstrip('/') + path, data=body, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def post(path, payload, headers=None):
        code, raw = request(path, 'POST', json.dumps(payload).encode(),
                            {'Content-Type': 'application/json', **(headers or {})})
        return code, json.loads(raw)

    checks = []
    def check(condition, name):
        if not condition:
            raise RuntimeError('Synthetic check failed: ' + name)
        checks.append(name)

    code, login = post('/api/v1/auth/login', {
        'username': os.environ.get('ADMIN_USERNAME', 'admin'),
        'password': os.environ['ADMIN_INITIAL_PASSWORD']})
    check(code == 200 and bool(login.get('access_token')), 'admin_login')
    admin = {'Authorization': 'Bearer ' + login['access_token']}
    code, raw = request('/api/v1/sessions', headers=admin)
    check(code == 200, 'authenticated_dashboard_sessions')
    sessions_before = len(json.loads(raw)['sessions'])
    code, raw = request('/api/v1/sessions')
    check(code == 401, 'unauthenticated_dashboard_denied')
    code, _ = post('/api/v1/devices/register', {'name':'SYNTHETIC TEST DEVICE'})
    check(code == 403, 'unpaired_device_denied')
    code, device = post('/api/v1/devices/register', {
        'name':'SYNTHETIC TEST DEVICE — ' + label,
        'pairing_code': os.environ['DEVICE_PAIRING_CODE']})
    check(code == 200, 'private_pairing')
    auth = {'X-Device-Token': device['device_token']}
    session = 'synthetic_' + label + '_' + uuid.uuid4().hex[:8]
    wav = synthetic_wav()
    metrics = synthetic_metrics()

    def upload(approved, audio=wav):
        boundary = 'ScreamTest' + uuid.uuid4().hex
        fields = {'session_id':session,'seq_index':'1','start_time':'1000','end_time':'1003',
                  **metrics, 'device_id':device['device_id'], 'recording_kind':'full',
                  'sharing_authorized':'true' if approved else 'false',
                  'file_hash':hashlib.sha256(audio).hexdigest()}
        parts = []
        for key, value in fields.items():
            parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+key+'"\r\n\r\n'+str(value)+'\r\n').encode())
        parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="audio_file"; filename="synthetic_test.wav"\r\nContent-Type: audio/wav\r\n\r\n').encode())
        parts.extend([audio, ('\r\n--'+boundary+'--\r\n').encode()])
        code, raw = request('/api/v1/audio/upload', 'POST', b''.join(parts),
                            {**auth,'Content-Type':'multipart/form-data; boundary='+boundary})
        return code, json.loads(raw)

    code, _ = upload(False)
    check(code == 403, 'unapproved_sharing_denied')
    code, _ = upload(True, wav[:-2] + b'\x00\x00')
    check(code == 403, 'non_fixture_audio_denied')
    code, first = upload(True)
    check(code == 200, 'authorized_synthetic_upload')
    code, retry = upload(True)
    check(code == 200 and retry.get('segment_id') == first.get('segment_id'), 'retry_deduplication')
    sid = first['segment_id']
    code, raw = request('/api/v1/sessions/'+session+'/segments', headers=admin)
    segments = json.loads(raw)['segments']
    check(code == 200 and len(segments) == 1, 'one_segment_after_retry')
    seg = segments[0]
    bars = json.loads(seg['waveform_data'])
    check(len(bars) == 50 and min(bars) == 0 and max(bars) > .4, 'measured_waveform')
    check(math.isclose(seg['peak_db'], metrics['peak_db'], abs_tol=.001) and seg['is_highlight'], 'loudness_dbfs_highlight')
    check(seg['recording_kind'] == 'full' and seg['speech_confidence'] == 0, 'synthetic_audio_not_labelled_speech')
    code, raw = request('/api/v1/audio/'+sid+'/stream', headers={**admin,'Range':'bytes=0-43'})
    check(code == 206 and raw == wav[:44], 'authenticated_range_playback_bytes')
    code, raw = request('/api/v1/audio/'+sid+'/download', headers=admin)
    check(code == 200 and hashlib.sha256(raw).digest() == hashlib.sha256(wav).digest(), 'download_integrity')
    for suffix in ('stream','download'):
        code, _ = request('/api/v1/audio/'+sid+'/'+suffix)
        check(code == 401, 'unauthenticated_'+suffix+'_denied')
        code, _ = request('/api/v1/audio/'+sid+'/'+suffix, headers=auth)
        check(code == 401, 'device_'+suffix+'_denied')
    code, _ = post('/api/v1/storage/restore', {}, admin)
    check(code == 403, 'backup_import_denied_in_test_mode')
    print('SYNTHETIC_HTTP_CHECK '+json.dumps({'transport':label,'passed':checks,
          'sessions_before':sessions_before,'fixture_sha256':hashlib.sha256(wav).hexdigest()}), flush=True)
    return checks

if __name__ == '__main__':
    verify(os.environ['TEST_BASE_URL'], 'public_https')
