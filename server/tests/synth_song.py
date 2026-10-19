"""Synthetic 'song' for pipeline tests: Karplus-Strong strummed chords + kick/snare + bass, two sections."""
import numpy as np
from scipy.signal import lfilter

SR = 22050
OPEN = [40, 45, 50, 55, 59, 64]
SHAPES = {  # 6th..1st string, -1 = muted
    "G": [3, 2, 0, 0, 0, 3], "D": [-1, -1, 0, 2, 3, 2], "Em": [0, 2, 2, 0, 0, 0], "C": [-1, 3, 2, 0, 1, 0],
    "Am": [-1, 0, 2, 2, 1, 0], "F": [1, 3, 3, 2, 1, 1], "A": [-1, 0, 2, 2, 2, 0], "E": [0, 2, 2, 1, 0, 0],
}
ROOT = {"G": 43, "D": 38, "Em": 40, "C": 36, "Am": 45, "F": 41, "A": 45, "E": 40}
_cache = {}


def pluck(midi, dur, rng):
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    n = int(round(SR / f0))
    key = (midi, round(dur, 2))
    if key not in _cache:
        L = int(dur * SR)
        x = np.zeros(L)
        burst = rng.uniform(-1, 1, n)
        burst = lfilter([0.6, 0.4], [1], burst)          # slightly dark (nylon-ish)
        x[:n] = burst
        g = 0.996 ** (110.0 / f0) if f0 > 110 else 0.996
        a = np.zeros(n + 2); a[0] = 1.0; a[n] = -0.5 * g; a[n + 1] = -0.5 * g
        _cache[key] = lfilter([1.0], a, x)
    return _cache[key]


def strum(out, t, chord, direction, amp, rng, ring, shift=0):
    frets = SHAPES[chord]
    strings = [i for i in range(6) if frets[i] >= 0]
    if direction == "U":
        strings = strings[::-1][:4]
    for k, s in enumerate(strings):
        start = int((t + k * 0.012) * SR)
        y = pluck(OPEN[s] + frets[s] + shift, ring, rng) * amp * (0.9 if direction == "U" else 1.0)
        end = min(len(out), start + len(y))
        if end > start:
            seg = y[: end - start].copy()
            fade = min(len(seg), int(0.03 * SR))
            seg[-fade:] *= np.linspace(1, 0, fade)
            out[start:end] += seg


def drum(out, t, kind, rng):
    start = int(t * SR)
    L = int(0.15 * SR)
    tt = np.arange(L) / SR
    if kind == "kick":
        y = np.sin(2 * np.pi * (50 + 60 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 18) * 0.9
    else:
        y = rng.normal(0, 1, L) * np.exp(-tt * 30) * 0.35
    end = min(len(out), start + L)
    out[start:end] += y[: end - start]


def bass(out, t, midi, dur):
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    L = int(dur * SR)
    tt = np.arange(L) / SR
    y = (np.sin(2 * np.pi * f0 * tt) + 0.3 * np.sin(4 * np.pi * f0 * tt)) * np.exp(-tt * 2.5) * 0.5
    start = int(t * SR)
    end = min(len(out), start + L)
    out[start:end] += y[: end - start]


# 16-step grid per bar: (step, dir)  -> "D DU UDU"
PATTERN = [(0, "D"), (4, "D"), (6, "U"), (10, "U"), (12, "D"), (14, "U")]


def make_song(bpm=100.0, seed=1, shift=0):
    """Returns (y, sr, truth, boundary): truth = list of (start, end, shape-name) – sounding chord = shape + shift semitones."""
    rng = np.random.default_rng(seed)
    beat = 60.0 / bpm
    verse = ["G", "D", "Em", "C"] * 4
    chorus = ["C", "G", "Am", "F"] * 4
    bars = verse + chorus
    lead = 1.0
    total = lead + len(bars) * 4 * beat + 2.0
    out = np.zeros(int(total * SR))
    truth = []
    for b, ch in enumerate(bars):
        t0 = lead + b * 4 * beat
        loud = 1.0 if b < 16 else 1.6
        truth.append((t0, t0 + 4 * beat, ch))
        for step, d in PATTERN:
            t = t0 + step * beat / 4 + rng.normal(0, 0.006)
            strum(out, t, ch, d, 0.25 * loud, rng, ring=0.9, shift=shift)
        for q in range(4):
            drum(out, t0 + q * beat, "kick" if q in (0, 2) else "snare", rng)
        r = ROOT[ch] + shift
        bass(out, t0, r - 12 if r > 40 else r, 4 * beat * 0.95)
    out /= np.max(np.abs(out)) * 1.1
    return out.astype(np.float32), SR, truth, lead + 16 * 4 * beat
