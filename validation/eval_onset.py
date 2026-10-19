"""Task 3: onset detector tuning (spectral flux) on synthetic strum/pluck sequences."""
import numpy as np, json, itertools
import dsp, synth
SR = 48000
rng = np.random.default_rng(7)
chords = list(synth.CHORDS.items())

def make_sequence(kind, bpm, n_events=24):
    beat = 60 / bpm
    step = beat / 2                      # eighth notes
    times, sig = [], np.zeros(int((n_events * step + 2.5) * SR))
    t = 0.5
    for i in range(n_events):
        if rng.random() < 0.2 and i > 0:  # rests make it realistic
            t += step; continue
        name, frets = chords[rng.integers(len(chords))]
        if kind == "strum":
            d = "D" if i % 2 == 0 else "U"
            y, hits = synth.strum(frets, d, dur=1.5, spread_ms=rng.uniform(8, 40), rng=rng, amp=rng.uniform(0.4, 1.0))
            onset = t
        else:
            s = rng.integers(0, 6)
            f0 = 440 * 2 ** ((synth.OPEN[s] + max(0, frets[s]) - 69) / 12)
            y = synth.pluck(f0, 1.5, rng=rng, amp=rng.uniform(0.3, 1.0)); onset = t
        a = int((t + rng.normal(0, 0.008)) * SR)   # human timing jitter does not matter: truth = actual
        onset = a / SR
        sig[a:a + len(y)] += y[:len(sig) - a]
        times.append(onset)
        t += step
    return sig, np.array(times)

def match(est, ref, tol=0.05):
    used = set(); tp = []; 
    for r in ref:
        best, bi = tol + 1, -1
        for i, e in enumerate(est):
            if i in used: continue
            if abs(e - r) < best: best, bi = abs(e - r), i
        if bi >= 0 and best <= tol:
            used.add(bi); tp.append(est[bi] - r)
    P = len(tp) / max(1, len(est)); R = len(tp) / max(1, len(ref))
    F = 2 * P * R / max(1e-9, P + R)
    return P, R, F, np.array(tp)

seqs = []
for kind in ["strum", "pluck"]:
    for bpm in [70, 100, 130]:
        for _ in range(2):
            s, tt = make_sequence(kind, bpm)
            x = synth.phone_mic(s, rng=rng, snr_db=rng.uniform(25, 40), hp_hz=130)
            seqs.append((kind, bpm, x, tt))

grid = list(itertools.product([0.3, 0.6, 1.0], [1.2, 1.5, 2.0]))
best = None; table = []
for delta, mult in grid:
    det = dsp.SpectralFlux(SR, delta=delta, mult=mult)
    Fs = {"strum": [], "pluck": []}; errs = []
    for kind, bpm, x, tt in seqs:
        on, _ = det.detect(x)
        on = np.array([dsp.refine_onset(x, SR, t) for t in on])
        P, R, F, d = match(on, tt)
        Fs[kind].append(F); errs.extend(d.tolist())
    errs = np.array(errs) * 1000
    row = dict(delta=delta, mult=mult, F_strum=float(np.mean(Fs["strum"])), F_pluck=float(np.mean(Fs["pluck"])),
               bias_ms=float(np.mean(errs)), sd_ms=float(np.std(errs)), p95_abs_ms=float(np.percentile(np.abs(errs - np.mean(errs)), 95)))
    table.append(row)
    score = row["F_strum"] + row["F_pluck"]
    if best is None or score > best[0]:
        best = (score, row)
for r in table:
    print(f"delta={r['delta']:.1f} mult={r['mult']:.1f}  F(strum)={r['F_strum']:.3f} F(pluck)={r['F_pluck']:.3f}  bias={r['bias_ms']:+.1f}ms sd={r['sd_ms']:.1f}ms p95|dev|={r['p95_abs_ms']:.1f}ms")
print("BEST", best[1])
json.dump({"grid": table, "best": best[1]}, open("results_onset_synth.json", "w"), indent=1)
