"""Nylon-string guitar synthesiser (extended Karplus-Strong + body + phone-mic model)
used for Phase-0 validation while no real recordings exist yet."""
import numpy as np
from scipy.signal import lfilter, butter, sosfilt

SR = 48000
OPEN = [40, 45, 50, 55, 59, 64]          # E2 A2 D3 G3 B3 E4  (index 0 = 6th string)

def _loop_coeffs(f0, sr, s):
    D = sr / f0
    frac_guess = D - int(D) 
    N = int(D - s) if D - s >= 2 else 2
    frac = D - N - s
    for _ in range(4):
        h = np.convolve([1 - s, s], [1 - frac, frac])
        w = 2 * np.pi * f0 / sr
        H = sum(h[k] * np.exp(-1j * w * k) for k in range(len(h)))
        phase_delay = -np.angle(H) / w
        err = D - (N + phase_delay)
        frac = min(0.999, max(0.0, frac + err))
    if frac >= 0.999:
        N += 1; frac = 0.0
    return N, np.convolve([1 - s, s], [1 - frac, frac])

def pluck(f0, dur, sr=SR, decay_s=None, brightness=0.35, rng=None, pos=0.18, amp=1.0):
    """brightness: 0 (bright) .. 0.5 (dark). Nylon ~ 0.3-0.45."""
    rng = rng or np.random.default_rng()
    n = int(dur * sr)
    s = brightness
    N, h = _loop_coeffs(f0, sr, s)
    if decay_s is None:                    # nylon: short-ish sustain, shorter for high notes
        decay_s = np.clip(3.5 * (110 / f0) ** 0.5, 0.8, 4.0)
    g = 10 ** (-3 / (decay_s * f0))        # -60 dB after decay_s
    a = np.zeros(N + len(h)); a[0] = 1.0
    a[N:N + len(h)] -= g * h
    exc = rng.uniform(-1, 1, N)
    exc = lfilter([1 - 0.5], [1, -0.5], exc)          # soften (flesh/nail)
    d = max(1, int(pos * N))                           # pluck position comb
    exc = exc - np.concatenate([np.zeros(d), exc[:-d]])
    x = np.zeros(n); x[:min(N, n)] = exc[:min(N, n)]
    y = lfilter([1.0], a, x)
    return amp * y / (np.abs(y).max() + 1e-9)

_BODY = None
def body_ir(sr=SR):
    global _BODY
    if _BODY is None:
        rng = np.random.default_rng(1)
        t = np.arange(int(0.08 * sr)) / sr
        ir = np.zeros_like(t); ir[0] = 1.0
        for f, q, a in ((100, 0.025, 0.6), (205, 0.02, 0.5), (390, 0.012, 0.3), (980, 0.006, 0.15)):
            ir += a * np.exp(-t / q) * np.sin(2 * np.pi * f * t)
        ir += 0.05 * rng.standard_normal(len(t)) * np.exp(-t / 0.01)
        _BODY = ir / np.abs(ir).sum() * 8
    return _BODY

def phone_mic(x, sr=SR, snr_db=35, hp_hz=120, rng=None, drr_db=None):
    """drr_db: direct-to-reverberant ratio (phone ~0.5 m from guitar in a room: ~3-12 dB)."""
    rng = rng or np.random.default_rng()
    y = np.convolve(x, body_ir(sr))[:len(x)]
    if drr_db is None:
        drr_db = rng.uniform(3, 12)
    t = np.arange(int(0.4 * sr)) / sr
    tail = rng.standard_normal(len(t)) * np.exp(-t / 0.08)
    tail[0] = 0.0
    tail *= np.sqrt(10 ** (-drr_db / 10) / (tail ** 2).sum())
    rir = tail; rir[0] = 1.0
    y = np.convolve(y, rir)[:len(x)]
    if hp_hz:
        y = sosfilt(butter(2, hp_hz, 'hp', fs=sr, output='sos'), y)
    p = np.mean(y ** 2) + 1e-12
    y = y + rng.standard_normal(len(y)) * np.sqrt(p / 10 ** (snr_db / 10))
    return y / (np.abs(y).max() + 1e-9) * 0.7

def strum(frets, direction, sr=SR, dur=1.2, spread_ms=25, rng=None, n_up_strings=None, amp=1.0):
    """frets: 6 values (6th..1st string), -1 = not played. direction 'D' or 'U'.
    Returns (signal, list of (string_index, onset_seconds))."""
    rng = rng or np.random.default_rng()
    strings = [i for i in range(6) if frets[i] >= 0]
    if direction == 'U':
        strings = strings[::-1]
        k = n_up_strings or rng.integers(3, len(strings) + 1)
        strings = strings[:k]
    n = int(dur * sr)
    out = np.zeros(n)
    hits = []
    step = spread_ms / 1000 / max(1, len(strings) - 1)
    t = 0.0
    for j, s in enumerate(strings):
        f0 = 440 * 2 ** ((OPEN[s] + frets[s] - 69) / 12)
        a = amp * rng.uniform(0.6, 1.0) * (0.75 if direction == 'U' and s < 2 else 1.0)
        start = int(t * sr)
        y = pluck(f0, dur - t, sr, rng=rng, brightness=rng.uniform(0.3, 0.42), amp=a)
        out[start:start + len(y)] += y[:n - start]
        hits.append((s, t))
        t += step * rng.uniform(0.6, 1.4)
    return out, hits

CHORDS = {   # 6th..1st string
    'C': [-1, 3, 2, 0, 1, 0], 'G': [3, 2, 0, 0, 0, 3], 'D': [-1, -1, 0, 2, 3, 2],
    'Am': [-1, 0, 2, 2, 1, 0], 'Em': [0, 2, 2, 0, 0, 0], 'E': [0, 2, 2, 1, 0, 0],
    'A': [-1, 0, 2, 2, 2, 0], 'Dm': [-1, -1, 0, 2, 3, 1], 'F': [1, 3, 3, 2, 1, 1],
    'G7': [3, 2, 0, 0, 0, 1], 'C7': [-1, 3, 2, 3, 1, 0], 'A7': [-1, 0, 2, 0, 2, 0],
    'E7': [0, 2, 0, 1, 0, 0], 'D7': [-1, -1, 0, 2, 1, 2], 'Bm': [-1, 2, 4, 4, 3, 2],
    'Fmaj7': [-1, -1, 3, 2, 1, 0], 'Cmaj7': [-1, 3, 2, 0, 0, 0], 'Am7': [-1, 0, 2, 0, 1, 0],
    'Em7': [0, 2, 0, 0, 0, 0], 'Dm7': [-1, -1, 0, 2, 1, 1],
}

def chord_midis(frets):
    return [OPEN[i] + f for i, f in enumerate(frets) if f >= 0]
