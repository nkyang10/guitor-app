import glob, os, re, sys, json, itertools
import numpy as np, librosa
sys.path.insert(0, "/data/guitar/server"); sys.path.insert(0, "/data/guitar/server/tests")
import analysis
SR, HOP = analysis.SR, analysis.HOP
cache = "/data/guitar/validation/oenv_cache.npz"
if not os.path.exists(cache):
    import soundfile as sf
    from synth_song import make_song
    data = {}
    for p in sorted(glob.glob("/data/datasets/guitarset/audio_mic/**/*_mic.wav", recursive=True)):
        name = os.path.basename(p)[:-8]
        m = re.match(r"\d\d_([A-Za-z]+)\d-(\d+)-", name)
        y, _ = librosa.load(p, sr=SR, mono=True)
        data[f"gs|{name}|{m.group(2)}"] = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP, aggregate=np.median)
    for bpm, seed, shift in [(92,5,0),(100,1,0),(84,2,3),(120,7,2),(72,9,0),(136,3,0),(64,4,0),(108,11,1),(128,12,0),(76,13,2)]:
        y, sr, truth, b = make_song(bpm=bpm, seed=seed, shift=shift)
        data[f"syn|{bpm}_{seed}|{bpm}"] = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP, aggregate=np.median)
    np.savez(cache, **data)
D = dict(np.load(cache))
ACS = {}
for k, o in D.items():
    x = o - o.mean(); ac = librosa.autocorrelate(x); ACS[k] = ac / (ac[0] + 1e-9)
fps = SR / HOP

def est(ac, w, prior, sigma, mix, lo=55, hi=190):
    n = len(ac)
    bpms = np.arange(lo, hi + 0.25, 0.5)
    L = 60.0 * fps / bpms
    def A(lag):
        i = lag.astype(int)
        ok = (i >= 1) & (i + 2 < n)
        i = np.clip(i, 1, n - 3)
        v = np.maximum.reduce([ac[i - 1], ac[i], ac[i + 1], ac[i + 2]])
        return np.where(ok, v, 0)
    s = w[0] * A(L) + w[1] * A(2 * L) + w[2] * A(4 * L) + w[3] * A(L / 2) + w[4] * A(3 * L)
    p = np.exp(-0.5 * (np.log2(bpms / prior) / sigma) ** 2)
    sc = s * ((1 - mix) + mix * p)
    return bpms[int(np.argmax(sc))]

def cls(e, r):
    q = e / r
    for name, f in [("ok", 1), ("double", 2), ("half", .5), ("3/2", 1.5), ("2/3", 2 / 3)]:
        if abs(q / f - 1) < 0.04:
            return name
    return "other"

def evaluate(params, keys):
    c = {}
    for k in keys:
        r = float(k.split("|")[2]); e = est(ACS[k], *params)
        z = cls(e, r); c[z] = c.get(z, 0) + 1
    n = len(keys)
    return c.get("ok", 0) / n, (c.get("ok", 0) + c.get("half", 0) + c.get("double", 0)) / n, c

gs_comp = [k for k in D if k.startswith("gs|") and k.split("|")[1].endswith("comp")]
gs_solo = [k for k in D if k.startswith("gs|") and k.split("|")[1].endswith("solo")]
syn = [k for k in D if k.startswith("syn|")]
cur = ((1, .5, .5, .25, 0), 100, .9, .4)
for name, keys in [("comp", gs_comp), ("solo", gs_solo), ("syn", syn)]:
    print("current", name, evaluate(cur, keys))
best = []
for w in [(1, .5, .5, .25, 0), (1, .5, .25, .25, 0), (1, .25, .25, .5, 0), (1, .5, .5, .5, 0), (1, .3, .3, .3, 0), (1, 0, 0, 0, 0),
          (1, .5, .5, 0, 0), (1, .5, .25, .5, 0), (1, .25, .5, .25, 0), (1, .5, .5, .25, -.3), (1, .5, .5, .5, -.3)]:
    for prior in (90, 100, 110, 120):
        for sigma in (.5, .7, .9, 1.2):
            for mix in (.4, .7, 1.0):
                P = (w, prior, sigma, mix)
                a1c, a2c, _ = evaluate(P, gs_comp); a1s, a2s, _ = evaluate(P, gs_solo); a1y, a2y, cy = evaluate(P, syn)
                best.append((a1c + 0.5 * a2c + 0.5 * a1s + 0.25 * a2s + 0.5 * a1y, P, round(a1c, 3), round(a2c, 3), round(a1s, 3), round(a2s, 3), round(a1y, 2), cy))
best.sort(key=lambda b: -b[0])
for b in best[:12]:
    print(b)
