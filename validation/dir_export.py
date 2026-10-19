"""Direction v2: cache features, test generic+personal MAP adaptation, export generic logreg to app assets."""
import numpy as np, json, os, gs
from scipy.signal import butter, sosfilt
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
import dir2feat as F
rng = np.random.default_rng(7)
cache = "dir2_feats.npz"
if os.path.exists(cache):
    z = np.load(cache, allow_pickle=True); X, y, P = z["X"], z["y"], z["P"]
else:
    X, y, P = [], [], []
    for name in gs.tracks("comp"):
        x, sr = gs.audio(name)
        if x is None: continue
        bk = F.bank(sr)
        for s in gs.strums(name):
            if s["label"] is None: continue
            f = F.feats(x, sr, s["t0"] - 0.012, bk)
            if f is None: continue
            X.append(f); y.append(1 if s["label"] == "D" else 0); P.append(name[:2])
    X, y, P = np.array(X), np.array(y), np.array(P)
    np.savez(cache, X=X, y=y, P=P)
print("features", X.shape, flush=True)
def bal(yt, pr):
    return 0.5 * (((pr == 1) & (yt == 1)).sum() / max(1, (yt == 1).sum()) + ((pr == 0) & (yt == 0)).sum() / max(1, (yt == 0).sum()))
def fit_generic(Xtr, ytr):
    sc = StandardScaler().fit(Xtr)
    lr = LogisticRegression(C=0.05, max_iter=3000, class_weight="balanced").fit(sc.transform(Xtr), ytr)
    return sc, lr.coef_[0].copy(), float(lr.intercept_[0])
def adapt(Z, yy, w0, b0, lam, iters=300, lr=0.1):
    """MAP logistic regression with Gaussian prior centred on generic weights (same as app code)."""
    w, b = w0.copy(), b0
    n = len(yy)
    # class balance weights
    cw = np.where(yy == 1, 0.5 / max(1, yy.sum()), 0.5 / max(1, (1 - yy).sum())) * n
    for _ in range(iters):
        p = 1 / (1 + np.exp(-(Z @ w + b)))
        g = (cw * (p - yy))
        gw = Z.T @ g / n + lam * (w - w0) / n
        gb = g.mean()
        w -= lr * gw; b -= lr * gb
    return w, b
res = {"generic_lopo": [], "personal_only": [], "adapt": {}, "gating": {}}
lams = [3.0, 10.0, 30.0, 100.0]
gate = {g: [] for g in (0.1, 0.2, 0.3)}
for p in sorted(set(P)):
    te = P == p; Xtr, ytr, Xte, yte = X[~te], y[~te], X[te], y[te]
    sc, w0, b0 = fit_generic(Xtr, ytr)
    Zte = sc.transform(Xte)
    pg = 1 / (1 + np.exp(-(Zte @ w0 + b0)))
    res["generic_lopo"].append(bal(yte, (pg >= 0.5).astype(int)))
    for g in gate:
        m = np.abs(pg - 0.5) >= g
        gate[g].append((float(m.mean()), bal(yte[m], (pg[m] >= 0.5).astype(int)) if m.sum() > 10 else np.nan))
    iD, iU = np.where(yte == 1)[0], np.where(yte == 0)[0]
    po, ad = [], {l: [] for l in lams}
    for rep in range(5):
        tr = np.concatenate([rng.choice(iD, 16, replace=False), rng.choice(iU, 16, replace=False)])
        rest = np.setdiff1d(np.arange(len(yte)), tr)
        lr_p = LogisticRegression(C=0.05, max_iter=3000).fit(Zte[tr], yte[tr])
        po.append(bal(yte[rest], lr_p.predict(Zte[rest])))
        for l in lams:
            w, b = adapt(Zte[tr], yte[tr], w0, b0, l)
            ad[l].append(bal(yte[rest], ((Zte[rest] @ w + b) >= 0).astype(int)))
    res["personal_only"].append(float(np.mean(po)))
    for l in lams: res["adapt"].setdefault(str(l), []).append(float(np.mean(ad[l])))
    print(p, "generic", round(res["generic_lopo"][-1], 3), "personal", round(np.mean(po), 3),
          {l: round(np.mean(ad[l]), 3) for l in lams}, flush=True)
summary = dict(generic_lopo=float(np.mean(res["generic_lopo"])), personal_only=float(np.mean(res["personal_only"])),
               adapt={l: float(np.mean(v)) for l, v in res["adapt"].items()},
               gating={str(g): dict(coverage=float(np.mean([c for c, _ in v])), balanced=float(np.nanmean([a for _, a in v]))) for g, v in gate.items()})
best_lam = max(summary["adapt"], key=lambda k: summary["adapt"][k])
summary["best_lambda"] = float(best_lam)
print(json.dumps(summary, indent=1), flush=True)
json.dump(dict(per_player=res, summary=summary), open("results_direction_v2_adapt.json", "w"), indent=1)
# final generic model on all players
sc, w0, b0 = fit_generic(X, y)
model = dict(version=2, kind="filterbank_logreg", nb=F.NB, edges=F.EDGES.tolist(), pre=0.025, post=0.06, step=0.002, warm=0.03,
             lp_hz=150.0, t0_offset=-0.012, mean=sc.mean_.tolist(), scale=sc.scale_.tolist(), w=w0.tolist(), b=b0,
             lambda_personal=float(best_lam),
             sos={str(sr): dict(bands=[F.butter(2, [F.EDGES[k], F.EDGES[k + 1]], 'bandpass', fs=sr, output='sos').tolist() for k in range(F.NB)],
                                lp=F.butter(2, 150, 'low', fs=sr, output='sos').tolist()) for sr in (44100, 48000)})
out = "/data/guitar/android/app/src/main/assets/direction_generic.json"
json.dump(model, open(out, "w"))
print("exported", out, os.path.getsize(out), flush=True)
# fixture for Kotlin unit test: deterministic signal
sr = 48000
t = np.arange(int(0.2 * sr)) / sr
sig = np.zeros_like(t)
for k, f in enumerate([82.4, 110, 146.8, 196, 246.9, 329.6]):
    st = 0.05 + 0.006 * k
    m = t >= st
    sig[m] += np.sin(2 * np.pi * f * (t[m] - st)) * np.exp(-(t[m] - st) * 6) * (0.5 + 0.1 * k)
sig += 0.001 * np.sin(2 * np.pi * 1234.5 * t)
f = F.feats(sig, sr, 0.05 + 0.012 + (-0.012), F.bank(sr))  # t0 such that window is valid
z = (f - sc.mean_) / sc.scale_
pd = float(1 / (1 + np.exp(-(z @ w0 + b0))))
os.makedirs("/data/guitar/android/app/src/test/resources", exist_ok=True)
json.dump(dict(sr=sr, t0=0.05, signal=sig.astype(np.float32).astype(float).round(7).tolist(), features=f.tolist(), prob_down=pd),
          open("/data/guitar/android/app/src/test/resources/direction_fixture.json", "w"))
print("fixture prob_down", pd)
