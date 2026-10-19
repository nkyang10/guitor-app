"""Real-recording checks on GuitarSet (mic audio): onset detector + tuner (MPM vs YIN)."""
import numpy as np, json
import dsp, gs
rng = np.random.default_rng(9)
res = {}
# ---------------- onsets
def ref_onsets(name, merge=0.03):
    ts = sorted(n[0] for n in gs.notes(name)); out = []
    for t in ts:
        if not out or t - out[-1] > merge: out.append(t)
    return np.array(out)
def match(est, ref, tol=0.05):
    used=set(); d=[]
    for r in ref:
        c=[(abs(e-r),i) for i,e in enumerate(est) if i not in used and abs(e-r)<=tol]
        if c: _,i=min(c); used.add(i); d.append(est[i]-r)
    P=len(d)/max(1,len(est)); R=len(d)/max(1,len(ref)); return P,R,2*P*R/max(1e-9,P+R),np.array(d)
for kind in ["comp", "solo"]:
    names = gs.tracks(kind); rng.shuffle(names); names = names[:24]
    for label, det in [("flux_0.3_1.5", dsp.SpectralFlux(44100, delta=0.3, mult=1.5)), ("flux_0.6_1.2", dsp.SpectralFlux(44100, delta=0.6, mult=1.2))]:
        Fs, Ps, Rs, D = [], [], [], []
        for n in names:
            x, sr = gs.audio(n)
            on, _ = det.detect(x)
            P, R, F, d = match(on, ref_onsets(n))
            Fs.append(F); Ps.append(P); Rs.append(R); D += list(d * 1000)
        D = np.array(D)
        res[f"onset_{kind}_{label}"] = dict(tracks=len(names), F=float(np.mean(Fs)), P=float(np.mean(Ps)), R=float(np.mean(Rs)),
                                            bias_ms=float(D.mean()), sd_ms=float(D.std()), p95_dev_ms=float(np.percentile(np.abs(D - D.mean()), 95)))
        print(kind, label, res[f"onset_{kind}_{label}"], flush=True)
# ---------------- tuner on isolated notes
errs = {"mpm": [], "yin": []}; gross = {"mpm": 0, "yin": 0}; none = {"mpm": 0, "yin": 0}; n_notes = 0
names = gs.tracks("solo"); rng.shuffle(names)
for name in names[:30]:
    x, sr = gs.audio(name); ns = gs.notes(name)
    for (t, d, m, s) in ns:
        if d < 0.15: continue
        a, b = t + 0.04, t + 0.04 + 2048 / sr
        if any(o is not None and (o[0] < b and o[0] + o[1] > a) and o != (t, d, m, s) for o in ns): continue
        fr = x[int(a * sr):int(a * sr) + 2048]
        if len(fr) < 2048: continue
        n_notes += 1
        for algo in ("mpm", "yin"):
            est, q = dsp.mpm(fr, sr) if algo == "mpm" else dsp.yin(fr, sr)
            if est <= 0: none[algo] += 1; continue
            c = 100 * (dsp.hz_to_midi(est) - m)
            if abs(c) > 50: gross[algo] += 1
            else: errs[algo].append(abs(c))
for algo in ("mpm", "yin"):
    e = np.array(errs[algo])
    res[f"tuner_real_{algo}"] = dict(notes=n_notes, med_cents=float(np.median(e)), p90_cents=float(np.percentile(e, 90)),
                                     gross_pct=100 * gross[algo] / n_notes, unvoiced_pct=100 * none[algo] / n_notes)
    print(algo, res[f"tuner_real_{algo}"], flush=True)
json.dump(res, open("results_real.json", "w"), indent=1)
