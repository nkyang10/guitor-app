"""Sweep onset-detector variants on real GuitarSet recordings (bias-compensated, tol 70 ms)."""
import numpy as np, json, gs
from scipy.ndimage import maximum_filter1d
rng = np.random.default_rng(9)
def ref_onsets(name, merge=0.03):
    ts = sorted(n[0] for n in gs.notes(name)); out = []
    for t in ts:
        if not out or t - out[-1] > merge: out.append(t)
    return np.array(out)
FR, HOP = 1024, 256
def stft_mag(x, sr):
    n = 1 + (len(x) - FR) // HOP
    idx = np.arange(FR)[None, :] + HOP * np.arange(n)[:, None]
    S = np.abs(np.fft.rfft(x[idx] * np.hanning(FR)[None, :], axis=1))
    f = np.fft.rfftfreq(FR, 1 / sr); band = (f >= 80) & (f <= 6000)
    return S[:, band]
def odf_of(S, lam, maxf, lag=1):
    L = np.log1p(lam * S)
    P = maximum_filter1d(L, 3, axis=1) if maxf else L
    o = np.zeros(len(L)); o[lag:] = np.maximum(L[lag:] - P[:-lag], 0).sum(1)
    return o
def pick(odf, delta, mult, sr, med_len=24, scale_len=280, min_ioi=0.06):
    on = []; last = -1e9; cs = np.concatenate([[0], np.cumsum(odf)])
    for i in range(2, len(odf) - 2):
        v = odf[i]
        if not (v >= odf[i - 1] and v >= odf[i + 1] and v >= odf[i + 2]): continue
        past = odf[max(0, i - med_len):i]
        a = max(0, i - scale_len); sc = max(1.0, (cs[i] - cs[a]) / max(1, i - a))
        if v > np.median(past) * mult + delta * sc:
            t = (i * HOP + FR / 2) / sr
            if t - last >= min_ioi: on.append(t); last = t
    return np.array(on)
tracks = []
for kind in ["comp", "solo"]:
    names = gs.tracks(kind); rng.shuffle(names)
    for n in names[:12]:
        x, sr = gs.audio(n); tracks.append((kind, n, stft_mag(x, sr), ref_onsets(n), len(x) / sr, sr))
rows = []
for lam in (1, 10, 100):
    for maxf in (False, True):
        for lag in (1, 2):
            O = [odf_of(S, lam, maxf, lag) for _, _, S, _, _, _ in tracks]
            for delta in (0.3, 0.6, 1.0, 1.5):
                for mult in (1.5,):
                    stats = {"comp": [], "solo": []}
                    for (kind, n, S, ref, dur, sr), odf in zip(tracks, O):
                        on = pick(odf, delta, mult, sr)
                        if len(on) == 0: stats[kind].append((0, 0, 0, 0, [])); continue
                        d = np.array([on[np.argmin(np.abs(on - r))] - r for r in ref])
                        b = np.median(d[np.abs(d) < 0.06]) if (np.abs(d) < 0.06).any() else 0
                        est = on - b; used = set(); dd = []
                        for r in ref:
                            c = [(abs(e - r), i) for i, e in enumerate(est) if i not in used and abs(e - r) <= 0.07]
                            if c: _, i = min(c); used.add(i); dd.append(est[i] - r)
                        P = len(dd) / len(est); R = len(dd) / len(ref)
                        far = sum(1 for e in est if np.min(np.abs(ref - e)) > 0.15) / (dur / 60)
                        stats[kind].append((2 * P * R / max(1e-9, P + R), R, far, b, dd))
                    row = dict(lam=lam, maxf=maxf, lag=lag, delta=delta, mult=mult)
                    for k in ("comp", "solo"):
                        st = stats[k]; dd = np.concatenate([np.array(s[4]) for s in st if len(s[4])]) * 1000
                        row[k] = dict(F=round(float(np.mean([s[0] for s in st])), 3), R=round(float(np.mean([s[1] for s in st])), 3),
                                      far_per_min=round(float(np.mean([s[2] for s in st])), 1), sd_ms=round(float(dd.std()), 1))
                    rows.append(row); print(row, flush=True)
json.dump(rows, open("results_onset_sweep_real.json", "w"), indent=1)
