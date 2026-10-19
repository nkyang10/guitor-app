"""GuitarSet onsets, bias-compensated + 'verify' view (recall within window, false triggers in gaps)."""
import numpy as np, json, dsp, gs
rng = np.random.default_rng(9)
def ref_onsets(name, merge=0.03):
    ts = sorted(n[0] for n in gs.notes(name)); out = []
    for t in ts:
        if not out or t - out[-1] > merge: out.append(t)
    return np.array(out)
res = {}
for kind in ["comp", "solo"]:
    names = gs.tracks(kind); rng.shuffle(names); names = names[:24]
    for label, det in [("strict", dsp.SpectralFlux(44100, delta=0.3, mult=1.5))]:
        allD = []; per = []
        for n in names:
            x, sr = gs.audio(n); on, odf = det.detect(x); ref = ref_onsets(n)
            d = np.array([on[np.argmin(np.abs(on - r))] - r for r in ref if len(on)])
            allD.append(np.median(d[np.abs(d) < 0.06]) if (np.abs(d) < 0.06).any() else 0)
            per.append((n, x, sr, on, ref))
        bias = float(np.median(allD))
        out = {}
        for tol in (0.05, 0.07):
            Fs, Ps, Rs, D, fpg = [], [], [], [], []
            for n, x, sr, on, ref in per:
                est = on - bias; used = set(); dd = []
                for r in ref:
                    c = [(abs(e - r), i) for i, e in enumerate(est) if i not in used and abs(e - r) <= tol]
                    if c: _, i = min(c); used.add(i); dd.append(est[i] - r)
                P = len(dd) / max(1, len(est)); R = len(dd) / max(1, len(ref)); Fs.append(2 * P * R / max(1e-9, P + R)); Ps.append(P); Rs.append(R); D += dd
                # false triggers far (>150 ms) from any reference onset, per minute
                far = [e for e in est if np.min(np.abs(ref - e)) > 0.15]
                fpg.append(len(far) / (len(x) / sr / 60))
            D = np.array(D) * 1000
            out[f"tol{int(tol*1000)}"] = dict(F=float(np.mean(Fs)), P=float(np.mean(Ps)), R=float(np.mean(Rs)), sd_ms=float(D.std()),
                                               p95_dev_ms=float(np.percentile(np.abs(D - D.mean()), 95)), far_false_per_min=float(np.mean(fpg)))
        out["bias_ms"] = bias * 1000; out["per_track_bias_sd_ms"] = float(np.std(allD) * 1000)
        res[f"{kind}_{label}"] = out
        print(kind, label, json.dumps(out), flush=True)
json.dump(res, open("results_real_onset2.json", "w"), indent=1)
