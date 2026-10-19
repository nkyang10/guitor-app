"""Task 6: Basic Pitch (Spotify, Apache-2.0) nmp.tflite on GuitarSet solo tracks.
 - transcription note F1 (onset ±50 ms, same MIDI pitch)
 - 'verify' mode: is expected note present? (Basic Pitch posteriorgram vs DSP harmonic presence)
 - CPU inference time per 2-s window."""
import numpy as np, json, time
from scipy.signal import resample_poly
from ai_edge_litert.interpreter import Interpreter
import gs, dsp
it = Interpreter(model_path="/data/models/nmp.tflite", num_threads=2); it.allocate_tensors()
inp = it.get_input_details()[0]["index"]
outs = {d["shape"][-1]: d["index"] for d in it.get_output_details()}
o_idx = [d["index"] for d in it.get_output_details() if d["shape"][-1] == 88]
WIN, OVL_FR = 43844, 30
HOP = WIN - OVL_FR * 256
FPS = 22050 / 256

def run(x22):
    """Returns note, onset posteriorgrams (frames x 88) for whole signal (basic-pitch windowing)."""
    pad = np.concatenate([np.zeros(OVL_FR * 256 // 2, np.float32), x22.astype(np.float32)])
    notes, onsets, times = [], [], []
    for a in range(0, len(pad), HOP):
        w = pad[a:a + WIN]
        if len(w) < WIN: w = np.concatenate([w, np.zeros(WIN - len(w), np.float32)])
        it.set_tensor(inp, w[None, :, None])
        t0 = time.perf_counter(); it.invoke(); times.append(time.perf_counter() - t0)
        A = it.get_tensor(o_idx[0])[0]; B = it.get_tensor(o_idx[1])[0]
        notes.append(A); onsets.append(B)
    def stitch(L):
        h = OVL_FR // 2
        return np.concatenate([l[h:-h] for l in L])
    return stitch(notes), stitch(onsets), times

def decode(note, onset, on_thr=0.5, fr_thr=0.3, min_len=11):
    res = []
    for p in range(88):
        o = onset[:, p]
        peaks = np.where((o[1:-1] > on_thr) & (o[1:-1] >= o[:-2]) & (o[1:-1] >= o[2:]))[0] + 1
        for k in peaks:
            e = k
            while e < len(note) and note[e, p] > fr_thr: e += 1
            if e - k >= min_len // 2:
                res.append((k / FPS, e / FPS, p + 21))
    return res

def f1(est, ref, tol=0.05):
    used = set(); tp = 0
    for (t, d, m, s) in ref:
        best = None
        for i, (te, ee, me) in enumerate(est):
            if i in used or me != round(m): continue
            if abs(te - t) <= tol and (best is None or abs(te - t) < abs(est[best][0] - t)): best = i
        if best is not None: used.add(best); tp += 1
    P = tp / max(1, len(est)); R = tp / max(1, len(ref))
    return P, R, 2 * P * R / max(1e-9, P + R)

rng = np.random.default_rng(3)
names = [n for n in gs.tracks("solo")]
rng.shuffle(names); names = names[:12]
# Which of the two 88-wide outputs is 'onset'? decide on first track: onset is peakier (lower mean)
order_checked = False
res = dict(tracks=[], verify={})
pos_bp, neg_bp, pos_dsp, neg_dsp, all_times = [], [], [], [], []
for name in names:
    x, sr = gs.audio(name)
    if x is None: continue
    x22 = resample_poly(x, 1, 2).astype(np.float32)
    A, B, times = run(x22); all_times += times
    if not order_checked:
        swap = A.mean() < B.mean()   # note posteriorgram has more mass than onset
        order_checked = True
    note, onset = (B, A) if swap else (A, B)
    ref = gs.notes(name)
    P, R, F = f1(decode(note, onset), ref)
    res["tracks"].append(dict(name=name, P=P, R=R, F=F, n_ref=len(ref)))
    # verify mode
    for (t, d, m, s) in ref:
        mi = int(round(m)); fr = int(t * FPS)
        a, b = max(0, fr - 4), min(len(note), fr + 7)
        if b <= a: continue
        score = lambda q: float(max(note[a:b, q - 21].max(), onset[a:b, q - 21].max())) if 21 <= q < 109 else 0.0
        sounding = {int(round(n[2])) for n in ref if n[0] - 0.1 <= t <= n[0] + n[1] + 0.05}
        wrong = [q for q in (mi - 2, mi - 1, mi + 1, mi + 2, mi - 5, mi + 5) if q not in sounding]
        if not wrong: continue
        q = int(rng.choice(wrong))
        pos_bp.append(score(mi)); neg_bp.append(score(q))
        mag, freqs = dsp.spectrum(x[int((t + 0.02) * sr):int((t + 0.02) * sr) + 4096], sr, 4096)
        pos_dsp.append(dsp.note_presence(mag, freqs, mi)); neg_dsp.append(dsp.note_presence(mag, freqs, q))
    print(name, round(F, 3))

def auc(p, n):
    p, n = np.asarray(p), np.asarray(n); return float((p[:, None] > n[None, :]).mean())
def best_acc(p, n):
    p, n = np.asarray(p), np.asarray(n); best = (0, 0)
    for t in np.unique(np.concatenate([p, n])):
        acc = 0.5 * ((p >= t).mean() + (n < t).mean())
        if acc > best[0]: best = (acc, t)
    return best
res["mean_F"] = float(np.mean([t["F"] for t in res["tracks"]]))
res["mean_P"] = float(np.mean([t["P"] for t in res["tracks"]])); res["mean_R"] = float(np.mean([t["R"] for t in res["tracks"]]))
acc_bp, thr_bp = best_acc(pos_bp, neg_bp); acc_dsp, thr_dsp = best_acc(pos_dsp, neg_dsp)
res["verify"] = dict(n=len(pos_bp), auc_basic_pitch=auc(pos_bp, neg_bp), auc_dsp=auc(pos_dsp, neg_dsp),
                     bal_acc_basic_pitch=acc_bp, thr_basic_pitch=float(thr_bp), bal_acc_dsp=acc_dsp, thr_dsp_db=float(thr_dsp),
                     tpr_bp_at_0p3=float((np.array(pos_bp) >= 0.3).mean()), fpr_bp_at_0p3=float((np.array(neg_bp) >= 0.3).mean()))
res["ms_per_window_cpu2"] = 1000 * float(np.median(all_times)); res["window_s"] = WIN / 22050
res["output_order_swapped"] = bool(swap)
print(json.dumps({k: v for k, v in res.items() if k != "tracks"}, indent=1))
json.dump(res, open("results_basic_pitch.json", "w"), indent=1)
