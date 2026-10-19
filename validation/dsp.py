"""Reference DSP used for Phase-0 validation.
The Kotlin implementation in the Android app mirrors these algorithms 1:1
(same parameters), so numbers here transfer to the app."""
import numpy as np

A4 = 440.0

def hz_to_midi(f):
    return 69 + 12 * np.log2(np.asarray(f, dtype=float) / A4)

def midi_to_hz(m):
    return A4 * 2 ** ((np.asarray(m, dtype=float) - 69) / 12)

def cents(f_est, f_ref):
    return 1200 * np.log2(f_est / f_ref)

# ---------------------------------------------------------------- pitch
def _parabolic(y, i):
    if i <= 0 or i >= len(y) - 1:
        return float(i), y[i]
    a, b, c = y[i - 1], y[i], y[i + 1]
    den = a - 2 * b + c
    if den == 0:
        return float(i), b
    p = 0.5 * (a - c) / den
    return i + p, b - 0.25 * (a - c) * p

def yin(frame, sr, fmin=70.0, fmax=1000.0, threshold=0.12):
    """YIN (de Cheveigne & Kawahara 2002). Returns (f0 or 0, aperiodicity)."""
    x = np.asarray(frame, dtype=np.float64)
    W = len(x) // 2
    tau_min = max(2, int(sr / fmax))
    tau_max = min(W - 1, int(sr / fmin) + 1)
    # difference function d(tau) for tau in [0, W)
    d = np.zeros(W)
    x0 = x[:W]
    for tau in range(1, tau_max + 2):
        diff = x0 - x[tau:tau + W]
        d[tau] = np.dot(diff, diff)
    cmnd = np.ones(tau_max + 2)
    running = 0.0
    for tau in range(1, tau_max + 2):
        running += d[tau]
        cmnd[tau] = d[tau] * tau / running if running > 0 else 1.0
    tau = tau_min
    found = -1
    while tau <= tau_max:
        if cmnd[tau] < threshold:
            while tau + 1 <= tau_max and cmnd[tau + 1] < cmnd[tau]:
                tau += 1
            found = tau
            break
        tau += 1
    if found < 0:
        found = tau_min + int(np.argmin(cmnd[tau_min:tau_max + 1]))
        if cmnd[found] > 0.35:
            return 0.0, float(cmnd[found])
    t, v = _parabolic(cmnd, found)
    return sr / t, float(cmnd[found])

def mpm(frame, sr, fmin=70.0, fmax=1000.0, k=0.93, min_clarity=0.6):
    """McLeod Pitch Method (NSDF + key maxima). Returns (f0 or 0, clarity)."""
    x = np.asarray(frame, dtype=np.float64)
    N = len(x)
    tau_max = min(N - 1, int(sr / fmin) + 2)
    n = np.zeros(tau_max + 1)
    # r(tau) via FFT autocorrelation, m(tau) via cumulative energy
    size = 1 << int(np.ceil(np.log2(2 * N)))
    X = np.fft.rfft(x, size)
    r = np.fft.irfft(X * np.conj(X), size)[:tau_max + 1]
    x2 = x * x
    cs = np.concatenate([[0.0], np.cumsum(x2)])
    for tau in range(tau_max + 1):
        m = (cs[N - tau] - cs[0]) + (cs[N] - cs[tau])
        n[tau] = 2 * r[tau] / m if m > 0 else 0.0
    tau_min = max(1, int(sr / fmax))
    # key maxima between positive-going zero crossings
    peaks = []
    pos = False
    cur_i, cur_v = -1, -1e9
    for tau in range(1, tau_max):
        if n[tau - 1] < 0 <= n[tau]:
            pos = True
            cur_i, cur_v = -1, -1e9
        elif n[tau - 1] >= 0 > n[tau] and pos:
            if cur_i >= 0:
                peaks.append(cur_i)
            pos = False
        if pos and n[tau] > cur_v and tau >= tau_min:
            cur_i, cur_v = tau, n[tau]
    if pos and cur_i >= 0:
        peaks.append(cur_i)
    if not peaks:
        return 0.0, 0.0
    best = max(n[p] for p in peaks)
    if best < min_clarity:
        return 0.0, float(best)
    for p in peaks:
        if n[p] >= k * best:
            t, v = _parabolic(n, p)
            return sr / t, float(min(1.0, v))
    return 0.0, 0.0

# ---------------------------------------------------------------- onsets
class SpectralFlux:
    """Real-time style log-spectral-flux onset detector.
    frame=1024, hop=256 @48k (5.3 ms); adaptive threshold = median(past) * mult + delta."""
    def __init__(self, sr, frame=1024, hop=256, fmin=80, fmax=6000,
                 delta=0.6, mult=1.5, med_len=24, min_ioi=0.06, lam=100.0):
        self.sr, self.frame, self.hop = sr, frame, hop
        self.win = np.hanning(frame)
        freqs = np.fft.rfftfreq(frame, 1 / sr)
        self.band = (freqs >= fmin) & (freqs <= fmax)
        self.delta, self.mult, self.med_len = delta, mult, med_len
        self.min_ioi, self.lam = min_ioi, lam

    def odf(self, x):
        n = 1 + (len(x) - self.frame) // self.hop
        prev = None
        out = np.zeros(n)
        for i in range(n):
            seg = x[i * self.hop:i * self.hop + self.frame] * self.win
            mag = np.log1p(self.lam * np.abs(np.fft.rfft(seg))[self.band])
            if prev is not None:
                out[i] = np.maximum(mag - prev, 0).sum()
            prev = mag
        return out

    def detect(self, x):
        odf = self.odf(x)
        hop_t = self.hop / self.sr
        onsets = []
        last = -1e9
        # normalise by running max to make delta scale-free
        for i in range(2, len(odf) - 2):
            past = odf[max(0, i - self.med_len):i]
            thr = (np.median(past) if len(past) else 0) * self.mult + self.delta * self._scale(odf, i)
            if odf[i] > thr and odf[i] >= odf[i - 1] and odf[i] >= odf[i + 1] and odf[i] >= odf[i + 2]:
                t = (i * self.hop + self.frame / 2) / self.sr
                if t - last >= self.min_ioi:
                    onsets.append(t)
                    last = t
        return np.array(onsets), odf

    def _scale(self, odf, i):
        # slowly-adapting level (mean of last ~1.5 s) so delta is relative
        past = odf[max(0, i - 280):i]
        return max(1.0, past.mean() if len(past) else 1.0)

def refine_onset(x, sr, t_coarse, search=0.03, win=0.0015):
    """Refine onset time to the point where the short-time energy rises fastest."""
    a = max(0, int((t_coarse - search) * sr))
    b = min(len(x), int((t_coarse + search / 2) * sr))
    seg = x[a:b]
    if len(seg) < 10:
        return t_coarse
    w = max(8, int(win * sr))
    env = np.sqrt(np.convolve(seg ** 2, np.ones(w) / w, mode='same'))
    d = np.diff(env)
    return (a + int(np.argmax(d))) / sr

# ---------------------------------------------------------------- chroma / chord
def spectrum(x, sr, n_fft=8192):
    seg = np.zeros(n_fft)
    m = min(len(x), n_fft)
    seg[:m] = x[:m] * np.hanning(m)
    return np.abs(np.fft.rfft(seg)), np.fft.rfftfreq(n_fft, 1 / sr)

def chroma_from_spectrum(mag, freqs, fmin=75, fmax=2500):
    c = np.zeros(12)
    sel = (freqs >= fmin) & (freqs <= fmax)
    m = hz_to_midi(freqs[sel])
    w = np.log1p(100 * mag[sel] / (mag.max() + 1e-12))   # compress -> less bass dominance
    pc = np.round(m).astype(int) % 12
    dev = np.abs(m - np.round(m))
    w = w * np.clip(1 - 2 * dev, 0, 1)                    # only bins near a semitone centre
    np.add.at(c, pc, w)
    return c / (np.linalg.norm(c) + 1e-12)

def expected_chroma(midis, n_harm=4):
    c = np.zeros(12)
    for m in midis:
        for h in range(1, n_harm + 1):
            mh = m + 12 * np.log2(h)
            c[int(round(mh)) % 12] += 1.0 / h
    return c / (np.linalg.norm(c) + 1e-12)

def chord_similarity(chroma, midis):
    return float(np.dot(chroma, expected_chroma(midis)))

def note_presence(mag, freqs, midi, width_cents=35):
    """Energy around f0 and 2*f0 of `midi`, relative to the local spectral floor (dB)."""
    f0 = midi_to_hz(midi)
    tot = 0.0
    for h, wgt in ((1, 1.0), (2, 0.7), (3, 0.4)):
        lo, hi = f0 * h * 2 ** (-width_cents / 1200), f0 * h * 2 ** (width_cents / 1200)
        sel = (freqs >= lo) & (freqs <= hi)
        if sel.any():
            tot += wgt * mag[sel].max()
    floor_sel = (freqs > f0 * 0.5) & (freqs < f0 * 4)
    floor = np.median(mag[floor_sel]) + 1e-9
    return 20 * np.log10(tot / floor + 1e-9)

# ---------------------------------------------------------------- strum direction
def direction_features(x, sr, t0):
    """Features describing how the spectrum evolves in the first ~50 ms of a strum.
    Down-strum: low strings first -> centroid rises; up-strum: high strings first."""
    hop = int(0.002 * sr)          # 2 ms
    n = int(0.004 * sr) * 2        # ~8 ms frames
    n = 1 << int(np.ceil(np.log2(n)))
    win = np.hanning(n)
    freqs = np.fft.rfftfreq(n, 1 / sr)
    low = (freqs >= 70) & (freqs < 300)
    high = (freqs >= 300) & (freqs < 3000)
    start = int((t0 - 0.010) * sr)
    cents_, el, eh = [], [], []
    for k in range(30):           # 60 ms
        a = start + k * hop
        if a < 0 or a + n > len(x):
            cents_.append(np.nan); el.append(0); eh.append(0); continue
        mag = np.abs(np.fft.rfft(x[a:a + n] * win))
        p = mag ** 2
        el.append(p[low].sum()); eh.append(p[high].sum())
        band = (freqs >= 70) & (freqs < 4000)
        cents_.append((freqs[band] * p[band]).sum() / (p[band].sum() + 1e-12))
    el, eh, cen = np.array(el), np.array(eh), np.array(cents_)
    cen = np.nan_to_num(cen, nan=np.nanmean(cen) if np.isfinite(cen).any() else 0)
    def rise_time(e):
        if e.max() <= 0:
            return 0.0
        c = np.cumsum(e) / (e.sum() + 1e-12)
        return float(np.argmax(c >= 0.3)) * 2.0     # ms to 30% of accumulated energy
    t_low, t_high = rise_time(el), rise_time(eh)
    logc = np.log2(cen + 1)
    # centroid slope over 0-40 ms after onset
    seg = logc[5:25]
    slope = np.polyfit(np.arange(len(seg)), seg, 1)[0] if len(seg) > 2 else 0.0
    early = logc[5:10].mean(); late = logc[18:25].mean()
    bass_ratio = np.log10((el.sum() + 1e-9) / (eh.sum() + 1e-9))
    return np.array([t_high - t_low, slope * 100, late - early, bass_ratio])

FEATURE_NAMES = ["t_high_minus_t_low_ms", "centroid_slope", "centroid_late_minus_early", "log_bass_ratio"]

class LogReg:
    """Tiny logistic regression (same as the on-device trainer)."""
    def __init__(self, l2=0.01, lr=0.2, epochs=600):
        self.l2, self.lr, self.epochs = l2, lr, epochs

    def fit(self, X, y):
        X = np.asarray(X, float); y = np.asarray(y, float)
        self.mu = X.mean(0); self.sd = X.std(0) + 1e-9
        Z = (X - self.mu) / self.sd
        self.w = np.zeros(Z.shape[1]); self.b = 0.0
        for _ in range(self.epochs):
            p = 1 / (1 + np.exp(-(Z @ self.w + self.b)))
            g = p - y
            self.w -= self.lr * (Z.T @ g / len(y) + self.l2 * self.w)
            self.b -= self.lr * g.mean()
        return self

    def proba(self, X):
        Z = (np.asarray(X, float) - self.mu) / self.sd
        return 1 / (1 + np.exp(-(Z @ self.w + self.b)))

class BandFlux:
    """Log band-energy flux (gain-invariant). 24 log-spaced bands 80 Hz-8 kHz, frame 1024, hop 256, lag 2.
    Threshold: max(local median * mult + delta, abs_floor)."""
    def __init__(self, sr, frame=1024, hop=256, n_bands=24, fmin=80, fmax=8000, lag=2,
                 delta=3.0, mult=1.0, med_len=16, min_ioi=0.06, floor_db=-75):
        self.sr, self.frame, self.hop, self.lag = sr, frame, hop, lag
        self.win = np.hanning(frame)
        freqs = np.fft.rfftfreq(frame, 1 / sr)
        edges = np.geomspace(fmin, fmax, n_bands + 1)
        self.bands = [(freqs >= edges[b]) & (freqs < edges[b + 1]) for b in range(n_bands)]
        self.bands = [b if b.any() else (np.abs(freqs - edges[i]) == np.abs(freqs - edges[i]).min()) for i, b in enumerate(self.bands)]
        self.delta, self.mult, self.med_len, self.min_ioi = delta, mult, med_len, min_ioi
        self.eps = (10 ** (floor_db / 20) * frame / 4) ** 2

    def odf(self, x):
        n = 1 + (len(x) - self.frame) // self.hop
        L = np.zeros((n, len(self.bands)))
        for i in range(n):
            p = np.abs(np.fft.rfft(x[i * self.hop:i * self.hop + self.frame] * self.win)) ** 2
            L[i] = [10 * np.log10(p[b].sum() + self.eps) for b in self.bands]
        out = np.zeros(n)
        out[self.lag:] = np.maximum(L[self.lag:] - L[:-self.lag], 0).sum(1) / len(self.bands)
        return out

    def detect(self, x):
        odf = self.odf(x)
        on = []; last = -1e9
        for i in range(2, len(odf) - 2):
            past = odf[max(0, i - self.med_len):i]
            thr = (np.median(past) if len(past) else 0) * self.mult + self.delta
            if odf[i] > thr and odf[i] >= odf[i - 1] and odf[i] >= odf[i + 1] and odf[i] >= odf[i + 2]:
                t = (i * self.hop + self.frame / 2 - self.lag * self.hop / 2) / self.sr
                if t - last >= self.min_ioi:
                    on.append(t); last = t
        return np.array(on), odf

class NormFlux(SpectralFlux):
    """Log-spectral flux normalised by a slowly decaying peak envelope (AGC), so the threshold is
    level-independent and does not rise with tempo. Chosen detector (see REPORT.md)."""
    def __init__(self, sr, delta=0.12, mult=1.3, med_len=16, decay_s=3.0, min_ioi=0.06, **kw):
        super().__init__(sr, min_ioi=min_ioi, **kw)
        self.delta, self.mult, self.med_len = delta, mult, med_len
        self.decay = 0.5 ** ((self.hop / sr) / decay_s)

    def detect(self, x):
        raw = self.odf(x)
        env = 1e-9; odf = np.zeros_like(raw)
        for i, v in enumerate(raw):
            env = max(v, env * self.decay, 1e-9)
            odf[i] = v / env
        on = []; last = -1e9
        for i in range(2, len(odf) - 2):
            past = odf[max(0, i - self.med_len):i]
            thr = np.median(past) * self.mult + self.delta
            if odf[i] > thr and odf[i] >= odf[i - 1] and odf[i] >= odf[i + 1] and odf[i] >= odf[i + 2]:
                t = (i * self.hop + self.frame / 2) / self.sr
                if t - last >= self.min_ioi:
                    on.append(t); last = t
        return np.array(on), odf
