"""Song analysis pipeline (PC side) for the guitar trainer app.

download (yt-dlp) -> ffmpeg -> optional Demucs (htdemucs_6s, guitar / no_guitar stems) -> librosa beats ->
beat-synchronous CQT chroma -> maj/min/7/m7 templates + Viterbi (downbeat-aware change penalty) -> key ->
capo suggestion (same rule as the app) -> novelty-based sections -> strum-pattern matching per section ->
Chart JSON (same schema as the app's music/Chart.kt).
"""
import json
import os
import shutil
import subprocess
import sys

import numpy as np
import librosa

import chordlib

SR = 22050
HOP = 512
HERE = os.path.dirname(os.path.abspath(__file__))
PATTERNS = json.load(open(os.path.join(HERE, "patterns.json"), encoding="utf-8"))
NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
QUALS = [("", (0, 4, 7)), ("m", (0, 3, 7)), ("7", (0, 4, 7, 10)), ("m7", (0, 3, 7, 10))]
QUAL_PRIOR = {"": 0.0, "m": 0.0, "7": -0.035, "m7": -0.035}


def have_demucs():
    try:
        import demucs  # noqa: F401
        return True
    except Exception:
        return False


def have_ffmpeg():
    return shutil.which("ffmpeg") is not None


# ------------------------------------------------------------------ IO
def download(url, work, progress=lambda p, m: None, max_minutes=15):
    from yt_dlp import YoutubeDL
    os.makedirs(work, exist_ok=True)
    state = {}

    def hook(d):
        if d.get("status") == "downloading":
            tot = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if tot:
                progress(0.02 + 0.18 * d.get("downloaded_bytes", 0) / tot, "下載緊 YouTube 音訊…")

    opts = {"format": "bestaudio/best", "outtmpl": os.path.join(work, "src.%(ext)s"), "noplaylist": True,
            "quiet": True, "no_warnings": True, "progress_hooks": [hook], "max_filesize": 300 * 1024 * 1024}
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if (info.get("duration") or 0) > max_minutes * 60:
            raise ValueError(f"首歌太長（>{max_minutes} 分鐘）")
        info = ydl.extract_info(url, download=True)
        state["path"] = ydl.prepare_filename(info)
        state["title"] = info.get("title") or "YouTube"
    return state["path"], state["title"]


def to_m4a(src, dst):
    if not have_ffmpeg():
        raise RuntimeError("搵唔到 ffmpeg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vn", "-ac", "2", "-c:a", "aac", "-b:a", "128k", dst], check=True)


def to_wav(src, dst, sr=44100):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vn", "-ac", "2", "-ar", str(sr), dst], check=True)


def separate(wav, out_dir, progress=lambda p, m: None):
    """Demucs 6-stem model, two-stem mode: guitar + no_guitar. Returns (guitar_wav, no_guitar_wav) or (None, None)."""
    if not have_demucs():
        return None, None
    progress(0.25, "分離結他音軌（Demucs，可能要幾分鐘）…")
    cmd = [sys.executable, "-m", "demucs", "-n", "htdemucs_6s", "--two-stems", "guitar", "-o", out_dir, wav]
    subprocess.run(cmd, check=True)
    base = os.path.splitext(os.path.basename(wav))[0]
    d = os.path.join(out_dir, "htdemucs_6s", base)
    g, ng = os.path.join(d, "guitar.wav"), os.path.join(d, "no_guitar.wav")
    return (g if os.path.exists(g) else None), (ng if os.path.exists(ng) else None)


# ------------------------------------------------------------------ analysis
def estimate_tempo(oenv, prior=120.0, lo=55.0, hi=190.0, sigma=0.5, mix=0.4):
    """Metrical autocorrelation scoring: a tempo T is good when the onset envelope repeats at the beat, the half note,
    the bar (4 beats) and the eighth. This suppresses the dotted-quarter (3/2) and double-time picks that a plain
    beat tracker makes on syncopated strum patterns. Log-normal prior around [prior] BPM.
    Tuned on GuitarSet (360 takes) + synthetic songs: see validation/eval_tempo.py / tune_tempo.py."""
    x = oenv - np.mean(oenv)
    ac = librosa.autocorrelate(x)
    ac = ac / (ac[0] + 1e-9)
    n = len(ac)
    fps = SR / HOP

    def A(lag):
        if lag < 1 or lag + 2 >= n:
            return 0.0
        i = int(lag)
        return float(max(ac[i - 1], ac[i], ac[i + 1], ac[i + 2]))

    bpms = np.arange(lo, hi + 0.25, 0.5)
    sc = np.empty(len(bpms))
    for k, T in enumerate(bpms):
        l = 60.0 * fps / T
        s = A(l) + 0.5 * A(2 * l) + 0.5 * A(4 * l) + 0.5 * A(l / 2)
        p = np.exp(-0.5 * (np.log2(T / prior) / sigma) ** 2)
        sc[k] = s * ((1 - mix) + mix * p)
    return float(bpms[int(np.argmax(sc))]), sc


def beats_and_tempo(y):
    oenv = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP, aggregate=np.median)
    t0, _ = estimate_tempo(oenv)
    tempo, beats = librosa.beat.beat_track(onset_envelope=oenv, sr=SR, hop_length=HOP, bpm=t0, tightness=300)
    tempo = float(np.atleast_1d(tempo)[0])
    bt = librosa.frames_to_time(beats, sr=SR, hop_length=HOP)
    if len(bt) >= 8:
        # least-squares slope of beat time vs index: avoids the ±1-frame quantisation bias of a median interval
        slope = float(np.polyfit(np.arange(len(bt)), bt, 1)[0])
        if slope > 0 and abs(60.0 / slope / t0 - 1) < 0.1:
            tempo = 60.0 / slope
    elif len(bt) >= 4:
        tempo = float(60.0 / np.median(np.diff(bt)))
    return tempo, bt, oenv


def _templates():
    T, names = [], []
    for r in range(12):
        for q, iv in QUALS:
            c = np.zeros(12)
            for k, i in enumerate(iv):
                m = 48 + r + i
                for h in range(1, 5):
                    c[int(round(m + 12 * np.log2(h))) % 12] += (1.0 if k == 0 else 0.85) / h
            T.append(c / np.linalg.norm(c)); names.append(NAMES[r] + q)
    return np.array(T), names


TEMPL, TNAMES = _templates()


def beat_features(y, beat_times):
    yh = librosa.effects.harmonic(y, margin=2.0)
    C = librosa.feature.chroma_cqt(y=yh, sr=SR, hop_length=HOP, fmin=librosa.note_to_hz("C2"), n_octaves=5, bins_per_octave=36)
    B = librosa.feature.chroma_cqt(y=yh, sr=SR, hop_length=HOP, fmin=librosa.note_to_hz("C1"), n_octaves=3, bins_per_octave=36)
    mf = librosa.feature.mfcc(y=y, sr=SR, hop_length=HOP, n_mfcc=13)
    rms = librosa.feature.rms(y=y, hop_length=HOP)[0]
    frames = librosa.time_to_frames(beat_times, sr=SR, hop_length=HOP)
    frames = np.clip(frames, 0, C.shape[1] - 1)
    Cs = librosa.util.sync(C, frames, aggregate=np.median)[:, 1:] if len(frames) else C[:, :0]
    Bs = librosa.util.sync(B, frames, aggregate=np.median)[:, 1:] if len(frames) else B[:, :0]
    Ms = librosa.util.sync(mf, frames, aggregate=np.mean)[:, 1:]
    Es = librosa.util.sync(rms[None, :], frames, aggregate=np.mean)[0, 1:]
    # sync() returns segments [0,f0),[f0,f1)...; drop the leading segment so column i = beat i..i+1
    n = len(beat_times)
    pad = lambda A: A[:, :n] if A.shape[1] >= n else np.concatenate([A, np.repeat(A[:, -1:], n - A.shape[1], 1)], 1)
    Cs, Bs, Ms = pad(Cs), pad(Bs), pad(Ms)
    Es = Es[:n] if len(Es) >= n else np.concatenate([Es, np.repeat(Es[-1:], n - len(Es))])
    Cs = Cs / (np.linalg.norm(Cs, axis=0, keepdims=True) + 1e-9)
    Bs = Bs / (Bs.max(axis=0, keepdims=True) + 1e-9)
    Es = Es / (Es.max() + 1e-9)
    return Cs, Bs, Ms, Es


def emissions(Cs, Bs, Es):
    sim = TEMPL @ Cs                                  # (48, n)
    roots = np.array([i // 4 for i in range(48)])
    sim = sim + 0.12 * Bs[roots, :]
    prior = np.array([QUAL_PRIOR[_qual(TNAMES[i])] for i in range(48)])
    sim = sim + prior[:, None]
    nrow = np.where(Es < 0.03, 0.9, 0.3)[None, :]      # no-chord state
    return np.vstack([sim, nrow])


def _qual(name):
    r, q = chordlib.split_root(name)
    return q


def viterbi(E, phase, kappa=14.0):
    S, n = E.shape
    score = kappa * E[:, 0].copy()
    back = np.zeros((n, S), dtype=int)
    for i in range(1, n):
        if phase < 0:
            pen = 3.5
        elif i % 4 == phase:
            pen = 2.0
        elif i % 2 == phase % 2:
            pen = 4.0
        else:
            pen = 6.0
        bp = int(np.argmax(score)); bv = score[bp]
        stay = score
        change = bv - pen
        use_change = change > stay
        back[i] = np.where(use_change, bp, np.arange(S))
        score = np.where(use_change, change, stay) + kappa * E[:, i]
    out = np.zeros(n, dtype=int)
    s = int(np.argmax(score))
    for i in range(n - 1, -1, -1):
        out[i] = s
        s = back[i, s]
    return out


def downbeat_phase(states, low_onset_at_beats):
    sc = np.zeros(4)
    for i in range(1, len(states)):
        if states[i] != states[i - 1]:
            sc[i % 4] += 1.0
    if len(low_onset_at_beats):
        lo = low_onset_at_beats / (low_onset_at_beats.max() + 1e-9)
        for i, v in enumerate(lo):
            sc[i % 4] += 0.3 * v
    return int(np.argmax(sc))


MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def estimate_key(Cs, chord_durs=None):
    """Krumhansl profile correlation + chord evidence: share of time on the tonic chord (I / i) and its dominant (V).
    The chord term resolves the usual relative major/minor confusion (G D Em C -> G, not Em)."""
    tot = Cs.sum(axis=1)
    shares = {}
    if chord_durs:
        total = sum(chord_durs.values()) or 1.0
        for name, d in chord_durs.items():
            try:
                r, q = chordlib.split_root(chordlib.normalize(name))
                pc = NAMES.index(chordlib.split_root(chordlib.normalize(r))[0])
            except Exception:
                continue
            minor = q.startswith("m") and not q.startswith("maj")
            shares[(pc, minor)] = shares.get((pc, minor), 0.0) + d / total
    best, bv = "C", -9
    for mode, prof in ((0, MAJOR), (1, MINOR)):
        for r in range(12):
            v = np.corrcoef(tot, np.roll(prof, r))[0, 1]
            if shares:
                tonic = shares.get((r, bool(mode)), 0.0)
                dom = shares.get(((r + 7) % 12, False), 0.0)        # V is major in both modes (harmonic minor)
                v += 0.6 * tonic + 0.3 * dom
            if v > bv:
                bv, best = v, NAMES[r] + ("m" if mode else "")
    return best


def _aligned_match(rseq, seq):
    """Best fraction of equal bar-chords over all relative offsets (sections may start mid-progression)."""
    best = 0.0
    if not rseq or not seq:
        return 0.0
    denom = min(len(seq), len(rseq))
    for off in range(-len(seq) + 1, len(rseq)):
        hits = sum(rseq[i + off] == seq[i] for i in range(len(seq)) if 0 <= i + off < len(rseq))
        best = max(best, hits / denom)
    return best


def novelty_sections(Cs, Ms, Es, downbeat_idx, labels, beat_times, bpm):
    """Checkerboard novelty on beat features -> boundaries on downbeats -> labels by chord-sequence similarity."""
    n = Cs.shape[1]
    le = np.log(Es + 1e-6)
    le = (le - le.mean()) / (le.std() + 1e-9)
    F = np.vstack([Cs, 0.5 * (Ms - Ms.mean(1, keepdims=True)) / (Ms.std(1, keepdims=True) + 1e-9) / np.sqrt(13),
                   0.6 * le[None, :]])
    F = F / (np.linalg.norm(F, axis=0, keepdims=True) + 1e-9)
    S = F.T @ F
    def novelty(L):
        k = np.ones((2 * L, 2 * L)); k[:L, L:] = -1; k[L:, :L] = -1
        g = np.exp(-0.5 * ((np.arange(2 * L) - L + 0.5) / (L / 2)) ** 2)
        k = k * np.outer(g, g)
        out = np.zeros(n)
        Sp = np.pad(S, L, mode="edge")
        for i in range(n):
            out[i] = (Sp[i:i + 2 * L, i:i + 2 * L] * k).sum()
        return np.maximum(out, 0)

    nov = novelty(16)                  # 4-bar context: finds section changes
    Ft = F[12:]                        # timbre + loudness only (chroma would pull boundaries to chord changes)
    Ft = Ft / (np.linalg.norm(Ft, axis=0, keepdims=True) + 1e-9)
    S = Ft.T @ Ft
    nov_fine = novelty(4)              # 1-bar context: pins the exact downbeat
    cands = [d for d in downbeat_idx if 8 <= d <= n - 12]
    bounds = [downbeat_idx[0] if downbeat_idx else 0]
    min_gap = 16                       # >= 4 bars
    thr = np.percentile(nov, 75) if n else 0
    for d in sorted(cands, key=lambda d: -nov[d]):
        if nov[d] < thr:
            break
        if all(abs(d - b) >= min_gap for b in bounds):
            bounds.append(d)
    dbs = set(downbeat_idx)
    refined = [bounds[0]]
    for b in bounds[1:]:
        opts = [c for c in (b - 4, b, b + 4) if c in dbs and 0 < c < n]
        refined.append(max(opts, key=lambda c: nov_fine[c]) if opts else b)
    bounds = sorted(set(refined))
    if len(bounds) == 1 and len(downbeat_idx) > 8:      # fallback: 8-bar blocks
        bounds = downbeat_idx[::8]
    segs = []
    end_t = beat_times[-1] + 60.0 / bpm
    for j, b in enumerate(bounds):
        e = bounds[j + 1] if j + 1 < len(bounds) else n
        segs.append((b, e))
    reps, letters = [], []
    for b, e in segs:
        seq = [labels[i] for i in range(b, e, 4)]
        meanf = F[:, b:e].mean(1)
        idx = -1
        for r, (rseq, rf) in enumerate(reps):
            same = _aligned_match(rseq, seq)
            cos = float(meanf @ rf / (np.linalg.norm(meanf) * np.linalg.norm(rf) + 1e-9))
            if same >= 0.6 or (same >= 0.5 and cos > 0.97):
                idx = r; break
        if idx < 0:
            reps.append((seq, meanf)); idx = len(reps) - 1
        letters.append(chr(ord("A") + idx))
    meanE = np.mean([Es[b:e].mean() for b, e in segs]) if segs else 0
    out = []
    for (b, e), L_ in zip(segs, letters):
        loud = Es[b:e].mean() > meanE * 1.1
        st = float(beat_times[b]); en = float(beat_times[e]) if e < n else float(end_t)
        out.append(dict(start=st, end=en, label=f"段落 {L_}" + ("（強）" if loud else ""), loud=bool(loud), b=b, e=e))
    return out


def pattern_vectors():
    out = {}
    for p in PATTERNS["strum"]:
        if p.get("beatsPerBar", 4) != 4:
            continue
        sub = p.get("subdivision", 2)
        v = np.zeros(16)
        for i, c in enumerate(p["steps"]):
            pos = i * (4 // sub) if sub in (1, 2, 4) else i
            if pos < 16:
                v[pos] = {"D": 1.0, "U": 0.8, "x": 0.9}.get(c, 0.0)
        out[p["id"]] = (v, p)
    return out


PVEC = pattern_vectors()


def bar_onset_vectors(oenv, beat_times, downbeat_idx):
    times = librosa.frames_to_time(np.arange(len(oenv)), sr=SR, hop_length=HOP)
    bars = []
    for d in downbeat_idx:
        if d + 4 >= len(beat_times):
            break
        v = np.zeros(16)
        for j in range(16):
            bi = d + j // 4
            t = beat_times[bi] + (beat_times[bi + 1] - beat_times[bi]) * (j % 4) / 4
            sel = (times > t - 0.03) & (times < t + 0.03)
            v[j] = oenv[sel].max() if sel.any() else 0
        if v.max() > 0:
            v = v / v.max()
        bars.append(v)
    return np.array(bars)


def suggest_patterns(bar_vecs, sections, bpm, level, downbeat_idx):
    """Per section: best correlation between bar onset vectors and pattern step vectors (difficulty-aware)."""
    res = []
    for s in sections:
        idx = [k for k, d in enumerate(downbeat_idx) if s["b"] <= d < s["e"] and k < len(bar_vecs)]
        best, bscore, scores = None, -9, {}
        for pid, (pv, p) in PVEC.items():
            lo, hi = p.get("bpm", [40, 200])
            if not (lo * 0.8 <= bpm <= hi * 1.25):
                continue
            if p["level"] > level + 1:
                continue
            if not idx:
                continue
            cs = [np.corrcoef(bar_vecs[k], pv)[0, 1] if bar_vecs[k].std() > 0 else 0 for k in idx]
            sc = float(np.nanmean(cs)) - 0.06 * max(0, p["level"] - level) - 0.01 * p["level"]
            scores[pid] = round(sc, 3)
            if sc > bscore:
                bscore, best = sc, pid
        if best is None or bscore < 0.15:
            best = rule_pattern(bpm, s["loud"])
        res.append((best, bscore, scores))
    return res


def rule_pattern(bpm, loud):
    if bpm < 80:
        return "s_folk" if loud else "s_ballad"
    if bpm < 115:
        return "s_pop1" if loud else "s_folk"
    if bpm < 140:
        return "s_alt" if loud else "s_quarter"
    return "s_eighth_down" if loud else "s_quarter"


def analyze_audio(y, title, source, level=1, guitar=None, progress=lambda p, m: None):
    """y: mono mix at SR. guitar: optional mono guitar stem at SR (better rhythm + chords)."""
    progress(0.55, "搵節拍…")
    bpm, beat_times, oenv = beats_and_tempo(y)
    if len(beat_times) < 8:
        raise ValueError("搵唔到穩定節拍")
    progress(0.65, "認和弦…")
    harm_src = guitar if guitar is not None else y
    Cs, Bs, Ms, Es = beat_features(harm_src, beat_times)
    E = emissions(Cs, Bs, Es)
    first = viterbi(E, -1)
    ylow = librosa.effects.preemphasis(y, coef=-0.95)          # crude low-pass emphasis for bass onsets
    olow = librosa.onset.onset_strength(y=ylow, sr=SR, hop_length=HOP)
    bf = np.clip(librosa.time_to_frames(beat_times, sr=SR, hop_length=HOP), 0, len(olow) - 1)
    phase = downbeat_phase(first, olow[bf])
    states = viterbi(E, phase)
    names = TNAMES + ["N"]
    labels = [names[s] for s in states]
    progress(0.8, "分段同揀節奏型…")
    spans = []
    i = 0
    while i < len(labels):
        j = i
        while j + 1 < len(labels) and labels[j + 1] == labels[i]:
            j += 1
        st = float(beat_times[i]); en = float(beat_times[j + 1]) if j + 1 < len(beat_times) else float(beat_times[j] + 60 / bpm)
        spans.append(dict(start=st, end=en, original=labels[i]))
        i = j + 1
    dur = {}
    for s in spans:
        if s["original"] != "N":
            dur[s["original"]] = dur.get(s["original"], 0) + s["end"] - s["start"]
    key = estimate_key(Cs, dur)
    capo, shapes = chordlib.suggest_capo(dur, level)
    for s in spans:
        s["label"] = shapes.get(s["original"], s["original"])
    downbeat_idx = list(range(phase, len(beat_times), 4))
    sections = novelty_sections(Cs, Ms, Es, downbeat_idx, labels, beat_times, bpm)
    rhythm_src = guitar if guitar is not None else librosa.effects.harmonic(y, margin=1.0)
    oenv_r = librosa.onset.onset_strength(y=rhythm_src, sr=SR, hop_length=HOP)
    bar_vecs = bar_onset_vectors(oenv_r, beat_times, downbeat_idx)
    pats = suggest_patterns(bar_vecs, sections, bpm, level, downbeat_idx)
    chart = dict(
        id="", title=title, bpm=round(bpm, 2), beatsPerBar=4,
        beats=[round(float(t), 4) for t in beat_times],
        downbeats=[round(float(beat_times[k]), 4) for k in downbeat_idx],
        chords=[dict(start=round(s["start"], 4), end=round(s["end"], 4), label=s["label"], original=s["original"]) for s in spans],
        sections=[dict(start=round(s["start"], 4), end=round(s["end"], 4), label=s["label"], pattern=p[0]) for s, p in zip(sections, pats)],
        events=[], key=key, capo=int(capo), source=source, audioOffset=0.0, kind="song",
        analysis=dict(version="1.0", separated=guitar is not None, level=level, duration=round(len(y) / SR, 2),
                      pattern_scores=[p[2] for p in pats], pattern_confidence=[round(p[1], 3) for p in pats]),
    )
    progress(0.95, "完成分析")
    return chart


def analyze_file(path, title, source, work, level=1, separate_stems=True, progress=lambda p, m: None):
    """Full pipeline from an audio file. Writes mix.m4a (+ no_guitar.m4a) into [work]. Returns (chart, has_no_guitar).
    Everything is decoded through ffmpeg -> WAV first, so any container yt-dlp returns (webm/opus, m4a, mp3...) works."""
    os.makedirs(work, exist_ok=True)
    progress(0.21, "轉換音訊…")
    guitar = None
    has_ng = False
    wav = None
    if have_ffmpeg():
        to_m4a(path, os.path.join(work, "mix.m4a"))
        wav = os.path.join(work, "src.wav")
        to_wav(path, wav)
    else:
        shutil.copy(path, os.path.join(work, "mix" + os.path.splitext(path)[1]))
    try:
        if separate_stems and wav and have_demucs():
            g, ng = separate(wav, os.path.join(work, "stems"), progress)
            if ng:
                to_m4a(ng, os.path.join(work, "no_guitar.m4a")); has_ng = True
            if g:
                guitar, _ = librosa.load(g, sr=SR, mono=True)
            shutil.rmtree(os.path.join(work, "stems"), ignore_errors=True)
        progress(0.5, "讀取音訊…")
        y, _ = librosa.load(wav or path, sr=SR, mono=True)
    finally:
        if wav and os.path.exists(wav):
            os.remove(wav)
    chart = analyze_audio(y, title, source, level, guitar, progress)
    return chart, has_ng
