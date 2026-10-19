"""Final onset config check: synthetic + real, strict and guided (weak ratio>=0.7 accepted inside expected windows)."""
import numpy as np, json, gs, synth
exec(open('sweep_real_onset.py').read().split("tracks = []")[0])
def cands(odf, delta, mult, sr, med_len=24, scale_len=280):
    out = []; cs = np.concatenate([[0], np.cumsum(odf)])
    for i in range(2, len(odf) - 2):
        v = odf[i]
        if not (v >= odf[i - 1] and v >= odf[i + 1] and v >= odf[i + 2]) or v <= 0: continue
        past = odf[max(0, i - med_len):i]; a = max(0, i - scale_len); sc = max(1.0, (cs[i] - cs[a]) / max(1, i - a))
        out.append(((i * HOP + FR / 2) / sr, v / (np.median(past) * mult + delta * sc)))
    return out
def evaluate(cs, ref, guided, dur, tol=0.07):
    # strict: ratio>1 with 60 ms IOI; guided: for each ref window, if no strict hit, take the strongest weak (>=0.7)
    strict = []; last = -1e9
    for t, r in cs:
        if r > 1 and t - last >= 0.06: strict.append(t); last = t
    strict = np.array(strict)
    d = np.array([strict[np.argmin(np.abs(strict - r))] - r for r in ref]) if len(strict) else np.zeros(1)
    b = np.median(d[np.abs(d) < 0.06]) if (np.abs(d) < 0.06).any() else 0
    hits = 0; dd = []
    for r in ref:
        near = [t for t in strict if abs(t - b - r) <= tol]
        if near: hits += 1; dd.append(min(near, key=lambda t: abs(t - b - r)) - b - r); continue
        if guided:
            w = [(rr, t) for t, rr in cs if 0.7 <= rr <= 1 and abs(t - b - r) <= tol]
            if w: hits += 1; dd.append(max(w)[1] - b - r)
    far = sum(1 for e in strict if np.min(np.abs(ref - (e - b))) > 0.15) / (dur / 60)
    return hits / len(ref), far, dd
CFG = {"A_lam1_lag2_d1.0": (1, 2, 1.0), "B_lam100_lag1_d0.3": (100, 1, 0.3), "C_lam10_lag2_d1.0": (10, 2, 1.0)}
res = {}
rng = np.random.default_rng(21)
# synthetic
chords = list(synth.CHORDS.items()); SR = 48000
syn = []
for kind in ["strum", "pluck"]:
    for bpm in [70, 100, 130, 160]:
        step = 60 / bpm / 2; n = 32; sig = np.zeros(int((n * step + 2.5) * SR)); times = []; t = 0.5
        prog = [chords[rng.integers(len(chords))] for _ in range(4)]
        for i in range(n):
            if rng.random() < 0.2 and i > 0: t += step; continue
            name, frets = prog[(i // 8) % 4]
            if kind == "strum": y, h = synth.strum(frets, "D" if i % 2 == 0 else "U", dur=1.5, spread_ms=rng.uniform(8, 40), rng=rng, amp=rng.uniform(0.4, 1))
            else:
                ss = [k for k in range(6) if frets[k] >= 0]; s = ss[rng.integers(len(ss))]
                y = synth.pluck(440 * 2 ** ((synth.OPEN[s] + frets[s] - 69) / 12), 1.5, rng=rng, amp=rng.uniform(0.3, 1))
            a = int((t + rng.normal(0, 0.008)) * SR); sig[a:a + len(y)] += y[:len(sig) - a]; times.append(a / SR); t += step
        x = synth.phone_mic(sig, rng=rng, snr_db=rng.uniform(25, 40), hp_hz=130)
        syn.append((kind, stft_mag(x, SR), np.array(times), len(x) / SR, SR))
real = []
for kind in ["comp", "solo"]:
    names = gs.tracks(kind); rng.shuffle(names)
    for n in names[:12]:
        x, sr = gs.audio(n); real.append((kind, stft_mag(x, sr), ref_onsets(n), len(x) / sr, sr))
for name, (lam, lag, delta) in CFG.items():
    row = {}
    for setname, data in (("synthetic", syn), ("real", real)):
        for guided in (False, True):
            agg = {}
            for kind, S, ref, dur, sr in data:
                cs = cands(odf_of(S, lam, False, lag), delta, 1.5, sr)
                R, far, dd = evaluate(cs, ref, guided, dur)
                agg.setdefault(kind, []).append((R, far, dd))
            for kind, v in agg.items():
                dd = np.concatenate([np.array(x[2]) for x in v]) * 1000
                row[f"{setname}_{kind}_{'guided' if guided else 'strict'}"] = dict(recall=round(float(np.mean([x[0] for x in v])), 3),
                    far_false_per_min=round(float(np.mean([x[1] for x in v])), 1), sd_ms=round(float(dd.std()), 1))
    res[name] = row; print(name, json.dumps(row), flush=True)
json.dump(res, open("results_onset_final.json", "w"), indent=1)
