import numpy as np
from scipy.signal import butter, sosfilt
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
