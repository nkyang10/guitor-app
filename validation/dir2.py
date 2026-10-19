"""Direction features v2: IIR filterbank envelopes -> per-band normalised cumulative-energy curves.
LOPO + personal calibration on GuitarSet comps; logistic regression and small MLP."""
import numpy as np, json, gs, sys
from scipy.signal import butter, sosfilt
from sklearn.linear_model import LogisticRegression
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline
rng = np.random.default_rng(5)
NB = 12
EDGES = np.geomspace(80, 5000, NB + 1)
def bank(sr):
    return [butter(2, [EDGES[b], EDGES[b + 1]], 'bandpass', fs=sr, output='sos') for b in range(NB)]
LP = {}
def feats(x, sr, t0, banks, pre=0.025, post=0.06, step=0.002):
    a = int((t0 - pre - 0.03) * sr); b = int((t0 + post) * sr)
    if a < 0 or b > len(x): return None
    seg = x[a:b]
    if sr not in LP: LP[sr] = butter(2, 150, 'low', fs=sr, output='sos')
    off = int(0.03 * sr)                      # filter warm-up
    curves = []; tons = []
    for sos in banks:
        e = sosfilt(LP[sr], np.abs(sosfilt(sos, seg)))[off:] ** 2
        hop = int(step * sr); n = len(e) // hop
        ee = e[:n * hop].reshape(n, hop).sum(1)
        c = np.cumsum(ee); c = c / (c[-1] + 1e-12)
        curves.append(c)
        tons.append([np.argmax(c >= q) * step * 1000 for q in (0.1, 0.25, 0.5)])
    C = np.array(curves)            # NB x T
    T = np.array(tons)              # NB x 3
    lvl = np.log10(np.array([sosfilt(s, seg[off:]).std() for s in banks]) + 1e-9)
    lvl = lvl - lvl.max()
    slope = [np.polyfit(np.arange(NB), T[:, k], 1)[0] for k in range(3)]
    return np.concatenate([C[:, ::2].ravel(), (T - T.mean(0)).ravel(), lvl, slope])
data = {}
for name in gs.tracks("comp"):
    x, sr = gs.audio(name)
    if x is None: continue
    bk = bank(sr)
    for s in gs.strums(name):
        if s["label"] is None: continue
        f = feats(x, sr, s["t0"] - 0.012, bk)
        if f is None: continue
        data.setdefault(name[:2], []).append((f, 1 if s["label"] == "D" else 0, s["spread"], len(s["strings"])))
print({p: len(v) for p, v in data.items()}, flush=True)
spreads = np.array([sp for v in data.values() for _, _, sp, _ in v]) * 1000
print("spread ms percentiles 10/50/90:", np.percentile(spreads, [10, 50, 90]).round(1))
def models():
    return {"logreg": make_pipeline(StandardScaler(), LogisticRegression(C=0.05, max_iter=2000, class_weight="balanced")),
            "mlp": make_pipeline(StandardScaler(), MLPClassifier((32,), alpha=1e-2, max_iter=800, random_state=0))}
res = {"lopo": {}, "personal": {}, "lopo_plus_personal": {}}
def bal(y, p):
    return 0.5 * (((p == 1) & (y == 1)).sum() / max(1, (y == 1).sum()) + ((p == 0) & (y == 0)).sum() / max(1, (y == 0).sum()))
for mname in ["logreg", "mlp"]:
    accs, bals, ups, downs = [], [], [], []
    pers = []
    for p in sorted(data):
        Xte = np.array([f for f, *_ in data[p]]); yte = np.array([l for _, l, *_ in data[p]])
        Xtr = np.array([f for q in data if q != p for f, *_ in data[q]]); ytr = np.array([l for q in data if q != p for _, l, *_ in data[q]])
        m = models()[mname].fit(Xtr, ytr); pr = m.predict(Xte)
        accs.append((pr == yte).mean()); bals.append(bal(yte, pr)); downs.append((pr[yte == 1] == 1).mean()); ups.append((pr[yte == 0] == 0).mean())
        # personal: 16+16 of this player
        iD = np.where(yte == 1)[0]; iU = np.where(yte == 0)[0]; pa = []
        for rep in range(3):
            tr = np.concatenate([rng.choice(iD, 16, replace=False), rng.choice(iU, 16, replace=False)]); te = np.setdiff1d(np.arange(len(yte)), tr)
            mp = make_pipeline(StandardScaler(), LogisticRegression(C=0.05, max_iter=2000)).fit(Xte[tr], yte[tr])
            pa.append(bal(yte[te], mp.predict(Xte[te])))
        pers.append(np.mean(pa))
    res["lopo"][mname] = dict(acc=float(np.mean(accs)), balanced=float(np.mean(bals)), down=float(np.mean(downs)), up=float(np.mean(ups)))
    res["personal"][mname] = dict(balanced=float(np.mean(pers)))
    print(mname, "LOPO", res["lopo"][mname], "personal(32, logreg)", res["personal"][mname], flush=True)
json.dump(res, open("results_direction_v2.json", "w"), indent=1)
