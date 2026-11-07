"""Deterministic tone/silence fixture. Contains no microphone data or speech."""
import io
import math
import struct
import wave


def synthetic_wav():
    samples = []
    for i in range(48000):
        # One quiet second, one silent second, one loud second.
        scale = 150 if i < 16000 else 0 if i < 32000 else 1800
        samples.append(((i % 32) - 16) * scale)
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(struct.pack('<48000h', *samples))
    return buf.getvalue()


def synthetic_metrics():
    pcm = struct.unpack('<48000h', synthetic_wav()[44:])
    peak = max(abs(s) for s in pcm)
    rms = math.sqrt(sum(s*s for s in pcm) / len(pcm))
    return {'duration_ms': 3000, 'peak_db': 20 * math.log10(peak / 32768),
            'avg_db': 20 * math.log10(rms / 32768), 'speech_confidence': 0.0}
