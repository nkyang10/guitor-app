"""Task 5: strum-direction classifier prototype.
Features (dsp.direction_features) + logistic regression.
 - synthetic nylon strums (phone-mic model)
 - GuitarSet comps: labels from per-string onset order; leave-one-player-out (generic)
   vs. personal calibration (train on 32 strums of the same player)."""
import numpy as np, json
import dsp, synth, gs
rng = np.random.default_rng(5)
res = {}

def evaluate(model, X, y, gate=0.8):
    p = model.proba(X)
    acc = float(((p > 0.5) == (y == 1)).mean())
    conf = (p >= gate) | (p <= 1 - gate)
    acc_g = float(((p[conf] > 0.5) == (y[conf] == 1)).mean()) if conf.any() else float("nan")
    accD = float(((p > 0.5) == (y == 1))[y == 1].mean()) if (y == 1).any() else float("nan")
    accU = float(((p > 0.5) == (y == 1))[y == 0].mean()) if (y == 0).any() else float("nan")
    return dict(acc=acc, acc_down=accD, acc_up=accU, gated_acc=acc_g, coverage=float(conf.mean()), n=int(len(y)))

# ---------- synthetic
X, y = [], []
names = list(synth.CHORDS)
for i in range(400):
    d = "D" if i % 2 == 0 else "U"
    frets = synth.CHORDS[names[rng.integers(len(names))]]
    sig, hits = synth.strum(frets, d, rng=rng, spread_ms=rng.uniform(8, 40), amp=rng.uniform(0.4, 1))
    x = synth.phone_mic(np.concatenate([np.zeros(4800), sig]), rng=rng, snr_db=rng.uniform(25, 40), hp_hz=130)
    t0 = dsp.refine_onset(x, 48000, 0.1 + rng.normal(0, 0.004))
    X.append(dsp.direction_features(x, 48000, t0)); y.append(1 if d == "D" else 0)
X, y = np.array(X), np.array(y)
m = dsp.LogReg().fit(X[:200], y[:200])
res["synthetic"] = evaluate(m, X[200:], y[200:])
res["synthetic"]["weights"] = dict(zip(dsp.FEATURE_NAMES, m.w.round(3).tolist()))
print("synthetic", res["synthetic"])

# ---------- GuitarSet
data = {}
for name in gs.tracks("comp"):
    x, sr = gs.audio(name)
    if x is None: continue
    player = name[:2]
    for s in gs.strums(name):
        if s["label"] is None: continue
        t0 = dsp.refine_onset(x, sr, s["t0"] + rng.normal(0, 0.005))
        f = dsp.direction_features(x, sr, t0)
        data.setdefault(player, []).append((f, 1 if s["label"] == "D" else 0, name))
summary = {p: dict(n=len(v), down=sum(1 for _, l, _ in v if l == 1)) for p, v in data.items()}
print("GuitarSet labelled strums per player:", summary)
res["guitarset_counts"] = summary
lopo, personal = [], []
for p in sorted(data):
    Xte = np.array([f for f, _, _ in data[p]]); yte = np.array([l for _, l, _ in data[p]])
    Xtr = np.array([f for q in data if q != p for f, _, _ in data[q]])
    ytr = np.array([l for q in data if q != p for _, l, _ in data[q]])
    if len(set(yte)) < 2: continue
    r = evaluate(dsp.LogReg().fit(Xtr, ytr), Xte, yte); r["player"] = p; lopo.append(r)
    # personal: 16 D + 16 U from this player for training, rest for testing (5 random splits)
    accs = []
    idxD = np.where(yte == 1)[0]; idxU = np.where(yte == 0)[0]
    if len(idxD) >= 24 and len(idxU) >= 24:
        for rep in range(5):
            trD = rng.choice(idxD, 16, replace=False); trU = rng.choice(idxU, 16, replace=False)
            tr = np.concatenate([trD, trU]); te = np.setdiff1d(np.arange(len(yte)), tr)
            accs.append(evaluate(dsp.LogReg().fit(Xte[tr], yte[tr]), Xte[te], yte[te]))
        personal.append(dict(player=p, acc=float(np.mean([a["acc"] for a in accs])),
                             acc_down=float(np.nanmean([a["acc_down"] for a in accs])),
                             acc_up=float(np.nanmean([a["acc_up"] for a in accs])),
                             gated_acc=float(np.nanmean([a["gated_acc"] for a in accs])),
                             coverage=float(np.mean([a["coverage"] for a in accs]))))
res["guitarset_lopo"] = lopo; res["guitarset_personal"] = personal
for r in lopo: print("LOPO", r)
for r in personal: print("PERSONAL", r)
if lopo:
    res["guitarset_lopo_mean"] = {k: float(np.nanmean([r[k] for r in lopo])) for k in ("acc", "acc_down", "acc_up", "gated_acc", "coverage")}
if personal:
    res["guitarset_personal_mean"] = {k: float(np.nanmean([r[k] for r in personal])) for k in ("acc", "acc_down", "acc_up", "gated_acc", "coverage")}
print("MEAN lopo", res.get("guitarset_lopo_mean")); print("MEAN personal", res.get("guitarset_personal_mean"))
json.dump(res, open("results_direction.json", "w"), indent=1)
