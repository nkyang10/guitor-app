"""Task 4: chord verification (chroma vs expected-voicing template) + per-string presence.
Data: synthetic nylon strums (phone-mic model) + GuitarSet comp strums (real, steel-string)."""
import numpy as np, json, sys
import dsp, synth, gs
rng = np.random.default_rng(11)

def auc(pos, neg):
    pos, neg = np.asarray(pos), np.asarray(neg)
    return float(((pos[:, None] > neg[None, :]).mean() + 0.5 * (pos[:, None] == neg[None, :]).mean()))

def thr_at_tpr(pos, tpr=0.95):
    return float(np.percentile(pos, 100 * (1 - tpr)))

def analyse(x, sr, t0, nfft):
    a = int((t0 + 0.03) * sr)
    seg = x[a:a + nfft]
    mag, freqs = dsp.spectrum(seg, sr, nfft)
    return mag, freqs, dsp.chroma_from_spectrum(mag, freqs)


def roc_table(pos, wrong, near, pres_on, pres_off):
    pos, wrong, near, pres_on, pres_off = map(np.asarray, (pos, wrong, near, pres_on, pres_off))
    sim = {f"{t:.2f}": dict(tpr=float((pos >= t).mean()), fpr_wrong=float((wrong >= t).mean()), fpr_near=float((near >= t).mean()))
           for t in (0.55, 0.60, 0.65, 0.70, 0.75, 0.80)}
    pres = {str(t): dict(on_pass=float((pres_on >= t).mean()), off_caught=float((pres_off < t).mean()) if len(pres_off) else None)
            for t in (15, 18, 20, 22, 25, 28, 30)}
    return dict(sim=sim, presence=pres)
res = {}
# ---------------- synthetic
names = list(synth.CHORDS)
pos, wrong, near = [], [], []
pres_on, pres_off = [], []
for trial in range(6):
    for name in names:
        frets = synth.CHORDS[name]
        y, hits = synth.strum(frets, "D", rng=rng, spread_ms=rng.uniform(10, 35))
        x = synth.phone_mic(np.concatenate([np.zeros(4800), y]), rng=rng, snr_db=rng.uniform(25, 40), hp_hz=130)
        mag, freqs, ch = analyse(x, 48000, 0.1, 8192)
        true = synth.chord_midis(frets)
        pos.append(dsp.chord_similarity(ch, true))
        other = synth.CHORDS[names[(names.index(name) + rng.integers(1, len(names))) % len(names)]]
        wrong.append(dsp.chord_similarity(ch, synth.chord_midis(other)))
        mod = list(true); k = rng.integers(len(mod)); mod[k] += rng.choice([-2, -1, 1, 2])
        near.append(dsp.chord_similarity(ch, mod))
        for m in true: pres_on.append(dsp.note_presence(mag, freqs, m))
    # missing-string test: same chord with one string muted
    for name in names:
        frets = list(synth.CHORDS[name]); played = [i for i in range(6) if frets[i] >= 0]
        k = played[rng.integers(len(played))]; miss_m = synth.OPEN[k] + frets[k]; frets[k] = -1
        y, _ = synth.strum(frets, "D", rng=rng, spread_ms=rng.uniform(10, 35))
        x = synth.phone_mic(np.concatenate([np.zeros(4800), y]), rng=rng, snr_db=rng.uniform(25, 40), hp_hz=130)
        mag, freqs, ch = analyse(x, 48000, 0.1, 8192)
        others = synth.chord_midis(frets)
        if all(abs((miss_m - o)) % 12 != 0 for o in others):   # only if no octave duplicate rings
            pres_off.append(dsp.note_presence(mag, freqs, miss_m))
res["synthetic"] = dict(n=len(pos), auc_wrong=auc(pos, wrong), auc_near=auc(pos, near),
                        thr95=thr_at_tpr(pos), fpr_wrong_at_thr=float(np.mean(np.array(wrong) >= thr_at_tpr(pos))),
                        fpr_near_at_thr=float(np.mean(np.array(near) >= thr_at_tpr(pos))),
                        pos_med=float(np.median(pos)), wrong_med=float(np.median(wrong)), near_med=float(np.median(near)),
                        presence_auc=auc(pres_on, pres_off), presence_on_p10=float(np.percentile(pres_on, 10)),
                        presence_off_p90=float(np.percentile(pres_off, 90)) if pres_off else None)
res["synthetic"]["roc"] = roc_table(pos, wrong, near, pres_on, pres_off)
print("synthetic", json.dumps(res["synthetic"], indent=0), flush=True)

# ---------------- GuitarSet (real)
pos, wrong, near = [], [], []
pres_on, pres_off = [], []
tr = gs.tracks("comp")
used = 0
for name in tr:
    if not any(s in name for s in ("SS", "Rock", "Funk")):  # strummed styles
        continue
    x, sr = gs.audio(name)
    if x is None: continue
    used += 1
    st = gs.strums(name)
    for i, s in enumerate(st):
        if i + 1 < len(st) and st[i + 1]["t0"] - s["t0"] < 0.2:  # need >=200 ms of ringing
            continue
        mag, freqs, ch = analyse(x, sr, s["t0"], 8192)
        true = [round(m) for m in s["midis"]]
        pos.append(dsp.chord_similarity(ch, true))
        root = rng.integers(0, 12); qual = [0, 4, 7] if rng.random() < .5 else [0, 3, 7]
        cand = [48 + root + q for q in qual] + [60 + root + q for q in qual]
        if set(c % 12 for c in cand) == set(t % 12 for t in true): continue
        wrong.append(dsp.chord_similarity(ch, cand))
        mod = list(true); k = rng.integers(len(mod)); mod[k] += int(rng.choice([-2, -1, 1, 2]))
        near.append(dsp.chord_similarity(ch, mod))
        for m in true: pres_on.append(dsp.note_presence(mag, freqs, m))
        # an absent note: random pitch in guitar range not sounding (no octave relation)
        for _ in range(2):
            q = int(rng.integers(40, 76))
            if all((q - t) % 12 != 0 for t in true):
                pres_off.append(dsp.note_presence(mag, freqs, q)); break
    if used >= 40: break
if pos:
    t95 = thr_at_tpr(pos)
    res["guitarset"] = dict(tracks=used, n=len(pos), auc_wrong=auc(pos, wrong), auc_near=auc(pos, near), thr95=t95,
                            fpr_wrong_at_thr=float(np.mean(np.array(wrong) >= t95)), fpr_near_at_thr=float(np.mean(np.array(near) >= t95)),
                            pos_med=float(np.median(pos)), wrong_med=float(np.median(wrong)), near_med=float(np.median(near)),
                            presence_auc=auc(pres_on, pres_off), presence_on_p10=float(np.percentile(pres_on, 10)),
                            presence_off_p90=float(np.percentile(pres_off, 90)))
    res["guitarset"]["roc"] = roc_table(pos, wrong, near, pres_on, pres_off)
    print("guitarset", json.dumps(res["guitarset"], indent=0))
json.dump(res, open("results_chord.json", "w"), indent=1)
