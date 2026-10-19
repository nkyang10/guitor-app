import numpy as np, json, os
import dir2feat as F
m = json.load(open("/data/guitar/android/app/src/main/assets/direction_generic.json"))
sr = 48000
t = np.arange(int(0.25 * sr)) / sr
sig = np.zeros_like(t)
for k, f in enumerate([82.4, 110, 146.8, 196, 246.9, 329.6]):
    st = 0.1 + 0.006 * k
    mm = t >= st
    sig[mm] += np.sin(2 * np.pi * f * (t[mm] - st)) * np.exp(-(t[mm] - st) * 6) * (0.5 + 0.1 * k)
sig += 0.001 * np.sin(2 * np.pi * 1234.5 * t)
sig = sig.astype(np.float32).astype(np.float64)
bank = [np.array(b) for b in m["sos"]["48000"]["bands"]]
F.LP[sr] = np.array(m["sos"]["48000"]["lp"])
f = F.feats(sig, sr, 0.1, bank)
z = (f - np.array(m["mean"])) / np.array(m["scale"])
pd = float(1 / (1 + np.exp(-(z @ np.array(m["w"]) + m["b"]))))
os.makedirs("/data/guitar/android/app/src/test/resources", exist_ok=True)
json.dump(dict(sr=sr, onset=4800, signal=sig.tolist(), features=f.tolist(), prob_down=pd),
          open("/data/guitar/android/app/src/test/resources/direction_fixture.json", "w"))
print("dim", len(f), "prob_down", pd)
