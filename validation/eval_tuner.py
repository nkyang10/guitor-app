"""Task 2: YIN vs MPM on synthetic nylon plucks (clean + phone-mic model)."""
import numpy as np, time, json, sys
import dsp, synth
SR = 48000
rng = np.random.default_rng(42)
notes = [40, 45, 50, 55, 59, 64, 43, 47, 52, 57, 62, 67, 69, 72, 76]
results = {}
for cond in ["clean", "phone"]:
    for frame in [2048, 4096]:
        for algo in ["yin", "mpm"]:
            results[(cond, frame, algo)] = {"err": [], "gross": 0, "n": 0, "ttl": [], "none": 0, "t": 0.0}
for trial in range(4):
    for m in notes:
        detune = rng.uniform(-50, 50)
        f = 440 * 2 ** ((m + detune / 100 - 69) / 12)
        y = synth.pluck(f, 2.0, rng=rng, brightness=rng.uniform(0.3, 0.45))
        pad = np.concatenate([np.zeros(int(0.1 * SR)), y])
        for cond in ["clean", "phone"]:
            x = pad if cond == "clean" else synth.phone_mic(pad, rng=rng, snr_db=rng.uniform(25, 40), hp_hz=rng.uniform(100, 180))
            for frame in [2048, 4096]:
                hop = 1024
                for algo in ["yin", "mpm"]:
                    r = results[(cond, frame, algo)]
                    stable = 0; ttl = None
                    for k, a in enumerate(range(int(0.1 * SR), int(1.8 * SR) - frame, hop)):
                        fr = x[a:a + frame]
                        t0 = time.perf_counter()
                        est, q = dsp.yin(fr, SR, fmin=70, fmax=1000) if algo == "yin" else dsp.mpm(fr, SR, fmin=70, fmax=1000)
                        r["t"] += time.perf_counter() - t0
                        r["n"] += 1
                        if est <= 0:
                            r["none"] += 1; stable = 0; continue
                        c = dsp.cents(est, f)
                        if abs(c) > 50:
                            r["gross"] += 1; stable = 0; continue
                        r["err"].append(abs(c))
                        stable = stable + 1 if abs(c) < 3 else 0
                        if stable == 3 and ttl is None:
                            ttl = (a + frame - int(0.1 * SR)) / SR
                    r["ttl"].append(ttl if ttl is not None else np.nan)
out = []
print(f"{'cond':6} {'frame':5} {'algo':4} {'med|c|':>7} {'p95|c|':>7} {'gross%':>7} {'none%':>6} {'stable_s':>8} {'ms/frame(py)':>12}")
for (cond, frame, algo), r in results.items():
    e = np.array(r["err"]); n = r["n"]
    row = dict(cond=cond, frame=frame, algo=algo, med=float(np.median(e)), p95=float(np.percentile(e, 95)),
               gross=100 * r["gross"] / n, none=100 * r["none"] / n, ttl=float(np.nanmedian(r["ttl"])),
               ms=1000 * r["t"] / n)
    out.append(row)
    print(f"{cond:6} {frame:5} {algo:4} {row['med']:7.2f} {row['p95']:7.2f} {row['gross']:7.2f} {row['none']:6.1f} {row['ttl']:8.3f} {row['ms']:12.2f}")
json.dump(out, open("results_tuner.json", "w"), indent=1)
