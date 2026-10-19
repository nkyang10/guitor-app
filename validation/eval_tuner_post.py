"""Task 2b: effect of post-processing (clarity gate + median-of-5) on phone-mic gross errors."""
import numpy as np, json
import dsp, synth
SR = 48000
rng = np.random.default_rng(43)
notes = [40, 45, 50, 55, 59, 64, 43, 47, 52, 57, 62, 67, 69, 72, 76]
stats = {k: {"err": [], "gross": 0, "n": 0, "shown": 0} for k in ["yin_raw", "mpm_raw", "mpm_gate", "mpm_gate_med5"]}
per_note_gross = {m: 0 for m in notes}; per_note_n = {m: 0 for m in notes}
for trial in range(4):
    for m in notes:
        f = 440 * 2 ** ((m + rng.uniform(-50, 50) / 100 - 69) / 12)
        y = synth.pluck(f, 2.0, rng=rng, brightness=rng.uniform(0.3, 0.45))
        x = synth.phone_mic(np.concatenate([np.zeros(4800), y]), rng=rng, snr_db=rng.uniform(25, 40), hp_hz=rng.uniform(100, 180))
        hist = []
        for a in range(4800, int(1.8 * SR) - 2048, 1024):
            fr = x[a:a + 2048]
            ey, _ = dsp.yin(fr, SR)
            em, cl = dsp.mpm(fr, SR)
            for key, est in (("yin_raw", ey), ("mpm_raw", em)):
                s = stats[key]; s["n"] += 1
                if est > 0:
                    s["shown"] += 1; c = dsp.cents(est, f)
                    if abs(c) > 50: s["gross"] += 1
                    else: s["err"].append(abs(c))
            gated = em if (em > 0 and cl >= 0.8) else 0
            s = stats["mpm_gate"]; s["n"] += 1
            if gated > 0:
                s["shown"] += 1; c = dsp.cents(gated, f)
                if abs(c) > 50: s["gross"] += 1
                else: s["err"].append(abs(c))
            if gated > 0: hist.append(dsp.hz_to_midi(gated))
            else: hist.append(np.nan)
            h = np.array(hist[-5:]); h = h[np.isfinite(h)]
            s = stats["mpm_gate_med5"]; s["n"] += 1
            if len(h) >= 3:
                est = dsp.midi_to_hz(np.median(h)); s["shown"] += 1; c = dsp.cents(est, f)
                per_note_n[m] += 1
                if abs(c) > 50: s["gross"] += 1; per_note_gross[m] += 1
                else: s["err"].append(abs(c))
out = {}
for k, s in stats.items():
    e = np.array(s["err"])
    out[k] = dict(shown_pct=100 * s["shown"] / s["n"], gross_pct_of_shown=100 * s["gross"] / max(1, s["shown"]),
                  med=float(np.median(e)), p95=float(np.percentile(e, 95)))
    print(k, {kk: round(v, 2) for kk, v in out[k].items()})
print("gross by midi (gate+med5):", {m: round(100 * per_note_gross[m] / max(1, per_note_n[m]), 1) for m in notes})
json.dump(out, open("results_tuner_post.json", "w"), indent=1)
