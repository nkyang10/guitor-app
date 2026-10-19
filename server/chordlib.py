"""Chord-symbol rules shared with the Android app (music/ChordNames.kt, Capo in ChordNames.kt).
chords.json / patterns.json are exported from the app so both sides use the same data."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
PCS = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6, "G": 7,
       "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11, "Cb": 11}
SHARP = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
QUAL = {"": "", "maj": "", "M": "", "min": "m", "m": "m", "-": "m", "7": "7", "dom7": "7", "maj7": "maj7", "M7": "maj7",
        "min7": "m7", "m7": "m7", "-7": "m7", "dim": "dim", "o": "dim", "dim7": "dim7", "hdim7": "m7b5", "m7b5": "m7b5",
        "aug": "aug", "+": "aug", "sus2": "sus2", "sus4": "sus4", "sus": "sus4", "6": "6", "maj6": "6", "m6": "m6", "min6": "m6",
        "9": "9", "maj9": "maj9", "m9": "m9", "min9": "m9", "add9": "add9", "7sus4": "7sus4"}
SHAPES = {c["name"]: c for c in json.load(open(os.path.join(HERE, "chords.json"), encoding="utf-8"))}


def split_root(s):
    r = s[:2] if len(s) > 1 and s[1] in "#b" else s[:1]
    return r, s[len(r):]


def normalize(sym):
    s = (sym or "").strip()
    if not s or s in ("N", "X"):
        return "N"
    s = s.split("/")[0].split("(")[0]
    if ":" in s:
        root0, rest0 = s.split(":", 1)
    else:
        root0, rest0 = split_root(s)
    if root0 not in PCS:
        return sym
    return SHARP[PCS[root0]] + QUAL.get(rest0, rest0)


def triad(sym):
    n = normalize(sym)
    if n == "N":
        return n
    root, q = split_root(n)
    minor = (q.startswith("m") and not q.startswith("maj")) or q.startswith("dim")
    return root + ("m" if minor else "")


def simplify(sym, level):
    n = normalize(sym)
    if n == "N" or level >= 3:
        return n
    root, q = split_root(n)
    keep2 = {"", "m", "7", "m7", "maj7", "sus2", "sus4"}
    base = n if (level == 2 and q in keep2) else triad(n)
    if level == 1:
        return {"F": "Fmaj7", "Bm": "Bm7", "B": "B7", "F#m": "F#m7", "Bb": "Bbmaj7", "Gm": "Gm7", "Eb": "Ebmaj7",
                "Fm": "F(small)"}.get(base, base)
    if base == "F":
        return "F(small)" if level == 2 else "F"
    return base


def transpose(sym, semis):
    n = normalize(sym)
    if n == "N" or semis == 0:
        return n
    root, q = split_root(n)
    if root not in PCS:
        return n
    return SHARP[(PCS[root] + semis) % 12] + q


def shape_for(sym, simplify_on, level):
    """Same lookup order as ChordLibrary.forSymbol."""
    n = normalize(sym)
    target = simplify(n, level) if simplify_on else n
    for cand in (target, simplify(target, 1), triad(target)):
        if cand in SHAPES:
            return SHAPES[cand]
    return None


def suggest_capo(durations, level, max_capo=7):
    """durations: sounding chord -> seconds. Returns (capo, {sounding: fingered shape name}). Mirrors Capo.suggest."""
    total = max(sum(durations.values()), 1e-6)
    best = None
    for c in range(max_capo + 1):
        cost, shapes = 0.0, {}
        for sym, d in durations.items():
            if normalize(sym) == "N":
                continue
            fing = transpose(sym, -c)
            sh = shape_for(fing, level < 3, level)
            lv = sh["level"] if sh else 5
            barre = 0.2 if sh and sh["barre"] > 0 else 0.0
            cost += d / total * (max(0, lv - level) + 0.15 * (lv - 1) + barre)
            shapes[sym] = sh["name"] if sh else fing
        cost += 0.08 * c
        if best is None or cost < best[1] - 1e-9:
            best = (c, cost, shapes)
    return best[0], best[2]
