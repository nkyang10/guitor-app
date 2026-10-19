"""GuitarSet helpers (Xi et al. 2018, CC BY 4.0). Steel-string acoustic, 6 players, mono mic audio @44.1k."""
import json, os, glob
import numpy as np, soundfile as sf
ROOT = "/data/datasets/guitarset"

def tracks(kind=None):
    names = sorted(os.path.basename(p)[:-5] for p in glob.glob(f"{ROOT}/annotation/*.jams"))
    if kind: names = [n for n in names if n.endswith("_" + kind)]
    return names

def audio(name):
    p = glob.glob(f"{ROOT}/audio_mic/**/{name}_mic.wav", recursive=True)
    if not p: return None, None
    x, sr = sf.read(p[0], dtype="float32")
    if x.ndim > 1: x = x.mean(1)
    return x, sr

def notes(name):
    d = json.load(open(f"{ROOT}/annotation/{name}.jams"))
    out = []
    for a in d["annotations"]:
        if a["namespace"] == "note_midi":
            s = int(a["annotation_metadata"]["data_source"])
            for o in a["data"]:
                out.append((o["time"], o["duration"], o["value"], s))
    out.sort()
    return out

def chords(name, performed=False):
    d = json.load(open(f"{ROOT}/annotation/{name}.jams"))
    cs = [a for a in d["annotations"] if a["namespace"] == "chord"]
    a = cs[1] if performed and len(cs) > 1 else cs[0]
    return [(o["time"], o["duration"], o["value"]) for o in a["data"]]

def tempo(name):
    d = json.load(open(f"{ROOT}/annotation/{name}.jams"))
    for a in d["annotations"]:
        if a["namespace"] == "tempo":
            return a["data"][0]["value"]

def strums(name, max_gap=0.06, min_strings=3):
    """Cluster note onsets across strings into strums. Returns list of dicts:
    t0, strings, onset times, midis, direction label ('D','U' or None if ambiguous)."""
    ns = notes(name)
    out, cur = [], []
    for n in ns:
        if cur and n[0] - cur[0][0] > max_gap:
            out.append(cur); cur = []
        cur.append(n)
    if cur: out.append(cur)
    res = []
    for c in out:
        strings = [n[3] for n in c]
        if len(set(strings)) < min_strings: 
            continue
        times = np.array([n[0] for n in c]); s = np.array(strings, float)
        spread = times.max() - times.min()
        label = None
        if spread >= 0.004:
            r = np.corrcoef(s, times)[0, 1] if np.std(times) > 0 else 0
            if r >= 0.7: label = "D"        # low string index (6th string) first
            elif r <= -0.7: label = "U"
        res.append(dict(t0=float(times.min()), strings=strings, times=times.tolist(),
                        midis=[n[2] for n in c], label=label, spread=float(spread)))
    return res

def sounding(name, t, margin=0.0):
    return [n for n in notes(name) if n[0] - margin <= t <= n[0] + n[1] + margin]
