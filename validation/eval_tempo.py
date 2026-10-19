"""Tempo estimation check on GuitarSet (tempo in file name) + synthetic songs: old (beat_track prior 110) vs new (metrical AC scoring)."""
import glob, json, os, re, sys, time
import numpy as np, librosa
sys.path.insert(0, "/data/guitar/server"); sys.path.insert(0, "/data/guitar/server/tests")
import analysis
SR, HOP = analysis.SR, analysis.HOP
ROOT = "/data/datasets/guitarset"

def old(y):
    oenv = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP, aggregate=np.median)
    tempo, beats = librosa.beat.beat_track(onset_envelope=oenv, sr=SR, hop_length=HOP, start_bpm=110, tightness=100)
    bt = librosa.frames_to_time(beats, sr=SR, hop_length=HOP)
    return float(60 / np.median(np.diff(bt))) if len(bt) >= 4 else float(np.atleast_1d(tempo)[0])

def new(y):
    return analysis.beats_and_tempo(y)[0]

def classify(est, ref):
    r = est / ref
    for name, f in [("ok", 1), ("double", 2), ("half", 0.5), ("3/2", 1.5), ("2/3", 2 / 3), ("triple", 3), ("third", 1 / 3)]:
        if abs(r / f - 1) < 0.04:
            return name
    return "other"

rows = []
files = sorted(glob.glob(f"{ROOT}/audio_mic/**/*_mic.wav", recursive=True))
which = sys.argv[1] if len(sys.argv) > 1 else "both"
t0 = time.time()
for p in files:
    name = os.path.basename(p)[:-8]
    m = re.match(r"\d\d_([A-Za-z]+)\d-(\d+)-", name)
    if not m:
        continue
    ref = float(m.group(2)); kind = "comp" if name.endswith("comp") else "solo"
    y, _ = librosa.load(p, sr=SR, mono=True)
    r = dict(name=name, style=m.group(1), kind=kind, ref=ref)
    if which in ("both", "old"):
        r["old"] = old(y); r["old_c"] = classify(r["old"], ref)
    if which in ("both", "new"):
        r["new"] = new(y); r["new_c"] = classify(r["new"], ref)
    rows.append(r)
def summary(key):
    out = {}
    for kind in ("comp", "solo"):
        rs = [r for r in rows if r["kind"] == kind and key + "_c" in r]
        if not rs:
            continue
        c = {}
        for r in rs:
            c[r[key + "_c"]] = c.get(r[key + "_c"], 0) + 1
        acc1 = c.get("ok", 0) / len(rs); acc2 = (c.get("ok", 0) + c.get("double", 0) + c.get("half", 0)) / len(rs)
        out[kind] = dict(n=len(rs), acc1=round(acc1, 3), acc2=round(acc2, 3), counts=c)
    return out
res = {k: summary(k) for k in ("old", "new") if any(k in r for r in rows)}
print(json.dumps(res, ensure_ascii=False, indent=1), f"{time.time()-t0:.0f}s")
json.dump(dict(summary=res, rows=rows), open(f"/data/guitar/validation/results_tempo_{which}.json", "w"), indent=1)
