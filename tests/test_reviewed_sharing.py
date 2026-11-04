"""Isolated security/integration tests; no production recordings or credentials."""
import hashlib
import io
import json
import os
import secrets
import tempfile
import unittest
import wave

_scratch = tempfile.TemporaryDirectory()
os.environ['SCREAM_STORAGE_DIR'] = _scratch.name
os.environ['JWT_SECRET'] = secrets.token_hex(32)
os.environ['ADMIN_SETUP_TOKEN'] = secrets.token_hex(32)
os.environ['DEVICE_PAIRING_CODE'] = secrets.token_hex(16)
os.environ.pop('ADMIN_INITIAL_PASSWORD', None)
from fastapi.testclient import TestClient
from backend import main

class ReviewedSharingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(main.app)
        cls.password = secrets.token_urlsafe(24)
        response = cls.client.post('/api/v1/auth/setup',
            headers={'X-Setup-Token': os.environ['ADMIN_SETUP_TOKEN']},
            json={'username': 'testadmin', 'password': cls.password})
        assert response.status_code == 200, response.text
        cls.admin = {'Authorization': 'Bearer ' + response.json()['access_token']}
        cls.refresh = response.json()['refresh_token']
        cls.device = cls.pair('first')
        cls.other = cls.pair('second')
        buffer = io.BytesIO()
        with wave.open(buffer, 'wb') as audio:
            audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
            audio.writeframes(b'\x00\x20' * 16000)
        cls.wav = buffer.getvalue()

    @classmethod
    def pair(cls, name):
        response = cls.client.post('/api/v1/devices/register', json={
            'name': name, 'pairing_code': os.environ['DEVICE_PAIRING_CODE']})
        assert response.status_code == 200, response.text
        return response.json()

    def upload(self, *, approved=True, device=None, session=None, extra=None, headers=None):
        device = device or self.device
        fields = dict(session_id=session or 'session_' + secrets.token_hex(6), seq_index='1',
            start_time='1000', end_time='1001', duration_ms='1000', peak_db='-12.04',
            avg_db='-12.04', device_id=device['device_id'], file_hash=hashlib.sha256(self.wav).hexdigest())
        if approved: fields['sharing_authorized'] = 'true'
        fields.update(extra or {})
        return self.client.post('/api/v1/audio/upload', data=fields,
            headers=headers if headers is not None else {'X-Device-Token': device['device_token']},
            files={'audio_file': ('test.wav', self.wav, 'audio/wav')})

    def test_private_setup_required(self):
        r = self.client.post('/api/v1/auth/setup', json={'username':'attacker','password':secrets.token_urlsafe(24)})
        self.assertEqual(r.status_code, 403)

    def test_pairing_required_and_tokens_not_reissued(self):
        r = self.client.post('/api/v1/devices/register', json={'id':self.device['device_id'],'name':'first'})
        self.assertEqual(r.status_code, 403)
        new = self.pair('first')
        self.assertNotEqual(new['device_token'], self.device['device_token'])
        self.assertNotEqual(new['device_id'], self.device['device_id'])

    def test_review_authorization_required(self):
        self.assertEqual(self.upload(approved=False).status_code, 403)

    def test_upload_requires_authentication(self):
        self.assertEqual(self.upload(headers={}).status_code, 401)

    def test_device_cannot_impersonate_other(self):
        self.assertEqual(self.upload(extra={'device_id':self.other['device_id']}).status_code, 403)

    def test_session_cannot_be_hijacked(self):
        session = 'session_' + secrets.token_hex(6)
        self.assertEqual(self.upload(session=session).status_code, 200)
        self.assertEqual(self.upload(session=session, device=self.other).status_code, 403)
        r = self.client.post('/api/v1/sessions/stop', json={'session_id':session},
            headers={'X-Device-Token': self.other['device_token']})
        self.assertEqual(r.status_code, 403)

    def test_upload_waveform_stream_download_integrity(self):
        session = 'session_' + secrets.token_hex(6)
        upload = self.upload(session=session)
        self.assertEqual(upload.status_code, 200, upload.text)
        sid = upload.json()['segment_id']
        rows = self.client.get(f'/api/v1/sessions/{session}/segments', headers=self.admin).json()['segments']
        self.assertEqual(len(rows), 1)
        bars = json.loads(rows[0]['waveform_data'])
        self.assertEqual(len(bars), 50)
        self.assertAlmostEqual(bars[0], .25, places=3)
        response = self.client.get(f'/api/v1/audio/{sid}/download', headers=self.admin)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, self.wav)
        response = self.client.get(f'/api/v1/audio/{sid}/stream', headers={**self.admin,'Range':'bytes=0-43'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, self.wav[:44])
        for suffix in ('stream','download'):
            self.assertEqual(self.client.get(f'/api/v1/audio/{sid}/{suffix}').status_code,401)
        self.assertEqual(self.client.get('/api/v1/sessions',headers={'X-Device-Token':self.device['device_token']}).status_code,401)

    def test_retry_is_idempotent(self):
        session = 'session_' + secrets.token_hex(6)
        first = self.upload(session=session)
        second = self.upload(session=session)
        self.assertEqual(first.json()['segment_id'], second.json()['segment_id'])
        self.assertEqual(second.json()['status'],'already_exists')

    def test_hash_mismatch_rejected(self):
        self.assertEqual(self.upload(extra={'file_hash':'0'*64}).status_code,400)

    def test_invalid_waveform_and_db_rejected(self):
        self.assertEqual(self.upload(extra={'waveform_json':'[NaN]'}).status_code,400)
        self.assertEqual(self.upload(extra={'peak_db':'50'}).status_code,400)

    def test_refresh_is_not_access_token(self):
        response=self.client.get('/api/v1/sessions',headers={'Authorization':'Bearer '+self.refresh})
        self.assertEqual(response.status_code,401)

    def test_compressed_audio_not_misread_as_pcm(self):
        self.assertEqual(main.compute_waveform_from_wav_bytes(b'not a wav header'), '[]')

if __name__ == '__main__': unittest.main()
