"""Accuracy check on real songs (task: 20-song evaluation).

CSV columns (see eval_songs_template.csv):
  url    YouTube link (or a local audio file path)
  title  optional
  bpm    reference BPM (optional)
  key    reference key, e.g. "G" or "Em" (optional)
  capo   capo used by the chord sheet (optional)
  chords reference chord sequence copied from a chord sheet, space separated, e.g. "G D Em C G D C"
  feel   your 1-5 rating after playing along in the app (optional, copied into the report)

Usage:
  python eval_songs.py eval_songs.csv                 # analyse directly (cached in data/eval/)
  python eval_songs.py eval_songs.csv --separate      # use Demucs guitar stem
  python eval_songs.py eval_songs.csv --server http://localhost:8765   # go through a running server (songs also appear in the app)
"""
import argparse
import csv
import json
import os
import sys
import tempfile
import time

import analysis
import chordlib

HERE = os.path.dirname(os.path.abspath(__file__))


def norm(c):
    if not c or c.upper() in ("N", "NC", "N.C.", "X"):
        return None
    try:
        r, q = chordlib.split_root(chordlib.normalize(c))
    except Exception:
        return None
    minor = q.startswith("m") and not q.startswith("maj")
    return r + ("m" if minor else "")


def collapse(seq):
    out = []
    for s in seq:
        if s and (not out or out[-1] != s):
            out.append(s)
    return out


def f1(a, b):
    a, b = set(a), set(b)
    if not a and not b:
        return 1.0
    tp = len(a & b)
    p = tp / len(b) if b else 0.0
    r = tp / len(a) if a else 0.0
    return 0.0 if p + r == 0 else 2 * p * r / (p + r)


def tempo_class(est, ref):
    q = est / ref
    for name, f in [("✓", 1), ("2×", 2), ("½", 0.5), ("3/2", 1.5), ("2/3", 2 / 3)]:
        if abs(q / f - 1) < 0.04:
            return name
    return "✗"


def key_class(est, ref):
    e, r = norm(est), norm(ref)
    if not e or not r:
        return "?"
    if e == r:
        return "✓"
    names = analysis.NAMES
    def pc(k):
        root = k.rstrip("m")
        return chordlib.split_root(chordlib.normalize(root))[0]
    try:
        ei, ri = names.index(pc(e)), names.index(pc(r))
    except ValueError:
        return "✗"
    if e.endswith("m") != r.endswith("m"):
        maj, mnr = (ei, ri) if not e.endswith("m") else (ri, ei)
        if (maj - mnr) % 12 == 3:
            return "relative"
    if (ei - ri) % 12 in (5, 7) and e.endswith("m") == r.endswith("m"):
        return "5th"
    return "✗"


def analyse_direct(url, separate, level):
    sid = analysis_id(url)
    d = os.path.join(HERE, "data", "eval", sid)
    cp = os.path.join(d, "chart.json")
    if os.path.exists(cp):
        return json.load(open(cp, encoding="utf-8"))
    os.makedirs(d, exist_ok=True)
    if os.path.exists(url):
        path, title, work = url, os.path.basename(url), None
    else:
        work = tempfile.mkdtemp(dir=d)
        path, title = analysis.download(url, work)
    chart, _ = analysis.analyze_file(path, title, url, d, level, separate)
    json.dump(chart, open(cp, "w", encoding="utf-8"), ensure_ascii=False)
    if work:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
    return chart


def analysis_id(url):
    import hashlib
    from app import canonical_url
    return hashlib.sha1(canonical_url(url).encode()).hexdigest()[:10]


def analyse_server(base, url, separate, level):
    import httpx
    r = httpx.post(base + "/analyze", json=dict(url=url, separate=separate, level=level), timeout=30).json()
    jid = r["job_id"]
    while True:
        j = httpx.get(f"{base}/jobs/{jid}", timeout=30).json()
        if j["status"] in ("done", "error"):
            break
        time.sleep(2)
    if j["status"] == "error":
        raise RuntimeError(j.get("error"))
    return httpx.get(f"{base}/songs/{j['song_id']}/chart", timeout=30).json()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--separate", action="store_true")
    ap.add_argument("--level", type=int, default=1)
    ap.add_argument("--server")
    ap.add_argument("--out", default="eval_report.md")
    a = ap.parse_args()
    rows = [r for r in csv.DictReader(open(a.csv, encoding="utf-8-sig")) if (r.get("url") or "").strip() and not r["url"].startswith("#")]
    results = []
    for i, r in enumerate(rows, 1):
        url = r["url"].strip()
        print(f"[{i}/{len(rows)}] {r.get('title') or url}", flush=True)
        try:
            chart = analyse_server(a.server.rstrip("/"), url, a.separate, a.level) if a.server else analyse_direct(url, a.separate, a.level)
        except Exception as e:  # noqa: BLE001
            results.append(dict(title=r.get("title") or url, error=str(e)[:200]))
            continue
        total = sum(c["end"] - c["start"] for c in chart["chords"]) or 1.0
        durs = {}
        for c in chart["chords"]:
            n = norm(c["original"])
            durs[n] = durs.get(n, 0) + c["end"] - c["start"]
        est_vocab = [k for k, v in durs.items() if k and v / total >= 0.02]
        ref_seq = collapse([norm(c) for c in (r.get("chords") or "").split()])
        est_seq = collapse([norm(c["original"]) for c in chart["chords"] if norm(c["original"])])
        ref_bi = {(x, y) for x, y in zip(ref_seq, ref_seq[1:])}
        est_bi = {(x, y) for x, y in zip(est_seq, est_seq[1:])}
        res = dict(title=r.get("title") or chart.get("title"), bpm=chart["bpm"], key=chart.get("key"), capo=chart.get("capo"),
                   n_sections=len(chart.get("sections", [])), none_share=round(durs.get(None, 0) / total, 3),
                   vocab_f1=round(f1(set(ref_seq), est_vocab), 3) if ref_seq else None,
                   bigram_f1=round(f1(ref_bi, est_bi), 3) if len(ref_seq) > 1 else None,
                   feel=r.get("feel") or "")
        if (r.get("bpm") or "").strip():
            res["bpm_ref"] = float(r["bpm"]); res["bpm_ok"] = tempo_class(chart["bpm"], float(r["bpm"]))
        if (r.get("key") or "").strip():
            res["key_ref"] = r["key"].strip(); res["key_ok"] = key_class(chart.get("key") or "", r["key"].strip())
        if (r.get("capo") or "").strip():
            res["capo_ref"] = int(r["capo"]); res["capo_ok"] = "✓" if int(r["capo"]) == chart.get("capo") else "✗"
        results.append(res)
    ok = [x for x in results if "error" not in x]
    def mean(k):
        v = [x[k] for x in ok if x.get(k) is not None]
        return sum(v) / len(v) if v else None
    def share(k, good):
        v = [x[k] for x in ok if k in x]
        return (sum(1 for s in v if s in good) / len(v)) if v else None
    lines = [f"# 歌曲分析準確度評估（{time.strftime('%Y-%m-%d')}，{len(ok)}/{len(results)} 首成功）", "",
             "| 指標 | 結果 |", "|---|---|"]
    for label, val in [("BPM 正確（±4%）", share("bpm_ok", {"✓"})), ("BPM 正確或半/雙倍", share("bpm_ok", {"✓", "½", "2×"})),
                       ("Key 正確", share("key_ok", {"✓"})), ("Key 正確或關係大小調", share("key_ok", {"✓", "relative"})),
                       ("Capo 同和弦譜一樣", share("capo_ok", {"✓"})), ("和弦詞彙 F1（平均）", mean("vocab_f1")),
                       ("和弦轉換 bigram F1（平均）", mean("bigram_f1")), ("無和弦(N)比例（平均）", mean("none_share"))]:
        lines.append(f"| {label} | {'—' if val is None else f'{val:.0%}'} |")
    lines += ["", "| 歌 | BPM (ref) | Key (ref) | Capo (ref) | 詞彙 F1 | bigram F1 | 段落 | feel |", "|---|---|---|---|---|---|---|---|"]
    for x in results:
        if "error" in x:
            lines.append(f"| {x['title']} | ✗ {x['error']} |||||||")
            continue
        lines.append(f"| {x['title']} | {x['bpm']:.0f} ({x.get('bpm_ref', '—')}) {x.get('bpm_ok', '')} | {x['key']} ({x.get('key_ref', '—')}) {x.get('key_ok', '')} | "
                     f"{x['capo']} ({x.get('capo_ref', '—')}) {x.get('capo_ok', '')} | {x['vocab_f1'] if x['vocab_f1'] is not None else '—'} | "
                     f"{x['bigram_f1'] if x['bigram_f1'] is not None else '—'} | {x['n_sections']} | {x['feel']} |")
    lines += ["", "目標（MVP 合格線）：BPM（含半/雙倍）≥ 90%，和弦詞彙 F1 ≥ 0.75，bigram F1 ≥ 0.5，feel 平均 ≥ 3.5。"]
    open(a.out, "w", encoding="utf-8").write("\n".join(lines))
    json.dump(results, open(os.path.splitext(a.out)[0] + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("\n".join(lines[:12]))
    print(f"→ {a.out}")


if __name__ == "__main__":
    sys.exit(main())
