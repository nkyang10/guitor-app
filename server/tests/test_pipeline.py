"""End-to-end tests: synthetic song -> analysis pipeline -> API.  Run:  python tests/test_pipeline.py   (or pytest)"""
import os
import sys
import tempfile
import time

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
os.environ.setdefault("GT_DATA", tempfile.mkdtemp(prefix="gt_data_"))

import analysis  # noqa: E402
import chordlib  # noqa: E402
from synth_song import make_song  # noqa: E402

PATTERN_IDS = {p["id"] for k in ("strum", "finger") for p in analysis.PATTERNS.get(k, [])}


def _same(a, b):
    ra, qa = chordlib.split_root(chordlib.normalize(a))
    rb, qb = chordlib.split_root(chordlib.normalize(b))
    return ra == rb and qa.startswith("m") == qb.startswith("m")


def _wav():
    y, sr, truth, boundary = make_song()
    path = os.path.join(tempfile.mkdtemp(), "synthetic_song.wav")
    sf.write(path, y, sr)
    return path, truth, boundary


def test_analysis_pipeline():
    path, truth, boundary = _wav()
    work = tempfile.mkdtemp()
    t0 = time.time()
    chart, has_ng = analysis.analyze_file(path, "synthetic", "test", work, level=1, separate_stems=False)
    took = time.time() - t0
    bpm = chart["bpm"]
    assert abs(bpm - 100) < 3, bpm
    # chord accuracy at each detected beat (original names vs truth)
    def truth_at(t):
        for s, e, c in truth:
            if s <= t < e:
                return c
        return None
    def chart_at(t):
        for c in chart["chords"]:
            if c["start"] <= t < c["end"]:
                return c["original"]
        return None
    hits = n = 0
    for t in chart["beats"]:
        tc = truth_at(t + 0.05)
        if tc is None:
            continue
        cc = chart_at(t + 0.05)
        n += 1
        hits += int(cc is not None and cc != "N" and _same(cc, tc))
    acc = hits / max(1, n)
    assert acc >= 0.8, acc
    # capo/shape consistency
    for c in chart["chords"]:
        if c["original"] != "N":
            assert chordlib.split_root(chordlib.normalize(c["label"]))[0] == \
                chordlib.split_root(chordlib.normalize(chordlib.transpose(c["original"], -chart["capo"])))[0], c
    assert 0 <= chart["capo"] <= 7
    # sections: one boundary near the verse/chorus switch, patterns valid
    secs = chart["sections"]
    assert len(secs) >= 2
    assert min(abs(s["start"] - boundary) for s in secs) < 1.3, [s["start"] for s in secs]
    first = secs[0]["label"].split("（")[0]
    chorus = [s for s in secs if abs(s["start"] - boundary) < 1.3][0]["label"].split("（")[0]
    assert first != chorus, (first, chorus)
    for s in secs:
        assert s["pattern"] in PATTERN_IDS, s["pattern"]
    assert os.path.exists(os.path.join(work, "mix.m4a"))
    assert not has_ng
    print(f"pipeline ok: bpm={bpm:.1f} chord_acc={acc:.1%} capo={chart['capo']} key={chart['key']} "
          f"sections={[(round(s['start'], 1), s['label'], s['pattern']) for s in secs]} took={took:.1f}s")
    return chart


def test_capo_song():
    """Same song sounding 3 semitones higher at 84 BPM (Bb F Gm Eb / Eb Bb Cm Ab) -> expect capo 3 with G-family shapes."""
    y, sr, truth, boundary = make_song(bpm=84.0, seed=2, shift=3)
    path = os.path.join(tempfile.mkdtemp(), "capo_song.wav")
    sf.write(path, y, sr)
    chart, _ = analysis.analyze_file(path, "capo", "test", tempfile.mkdtemp(), level=1, separate_stems=False)
    assert abs(chart["bpm"] - 84) < 3, chart["bpm"]
    hits = n = 0
    for t in chart["beats"]:
        tc = next((chordlib.transpose(c, 3) for s, e, c in truth if s <= t + 0.05 < e), None)
        if tc is None:
            continue
        cc = next((c["original"] for c in chart["chords"] if c["start"] <= t + 0.05 < c["end"]), None)
        n += 1
        hits += int(cc not in (None, "N") and _same(cc, tc))
    acc = hits / max(1, n)
    assert acc >= 0.8, acc
    assert chart["capo"] == 3, chart["capo"]
    shapes = {c["label"] for c in chart["chords"] if c["original"] != "N"}
    print(f"capo song ok: bpm={chart['bpm']:.1f} acc={acc:.1%} capo={chart['capo']} key={chart['key']} shapes={sorted(shapes)}")


def test_url_keys():
    import app
    a = app.canonical_url("https://youtu.be/dQw4w9WgXcQ?si=abc")
    b = app.canonical_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10")
    c = app.canonical_url("Never Gonna Give You Up https://m.youtube.com/watch?v=dQw4w9WgXcQ")
    d = app.canonical_url("https://youtube.com/shorts/dQw4w9WgXcQ")
    assert a == b == c == d == "youtube:dQw4w9WgXcQ", (a, b, c, d)
    assert app.clean_url("看下呢首 https://youtu.be/x1 !") == "https://youtu.be/x1"


def test_api_flow():
    from fastapi.testclient import TestClient
    import app
    cl = TestClient(app.app)
    h = cl.get("/health").json()
    assert h["ok"] and "demucs" in h and "ytdlp" in h
    path, truth, boundary = _wav()
    with open(path, "rb") as f:
        r = cl.post("/analyze_file", files={"file": ("synthetic_song.wav", f, "audio/wav")},
                    data={"separate": "false", "level": "1", "title": "測試歌"})
    assert r.status_code == 200, r.text
    job_id = r.json()["job_id"]
    t0 = time.time()
    while True:
        j = cl.get(f"/jobs/{job_id}").json()
        if j["status"] in ("done", "error"):
            break
        assert time.time() - t0 < 300, j
        time.sleep(0.5)
    assert j["status"] == "done", j
    sid = j["song_id"]
    songs = cl.get("/songs").json()
    assert any(s["id"] == sid and s["title"] == "測試歌" for s in songs), songs
    ch = cl.get(f"/songs/{sid}/chart").json()
    assert ch["id"] == sid and ch["kind"] == "song" and len(ch["beats"]) > 50
    a = cl.get(f"/songs/{sid}/audio", params={"stem": "mix"})
    assert a.status_code == 200 and len(a.content) > 10000 and a.headers["content-type"].startswith("audio/")
    assert cl.get(f"/songs/{sid}/audio", params={"stem": "no_guitar"}).status_code == 404
    assert cl.get(f"/songs/{sid}/audio", params={"stem": "x"}).status_code == 400
    assert cl.get("/songs/..%2Fetc/chart").status_code in (400, 404)
    # re-uploading the same file reuses the id
    with open(path, "rb") as f:
        r2 = cl.post("/analyze_file", files={"file": ("again.wav", f, "audio/wav")}, data={"separate": "false"})
    assert r2.status_code == 200
    j2 = r2.json()["job_id"]
    while cl.get(f"/jobs/{j2}").json()["status"] in ("queued", "running"):
        time.sleep(0.2)
    j2s = cl.get(f"/jobs/{j2}").json()
    assert j2s["status"] == "done" and j2s["song_id"] == sid and "快取" in j2s["message"], j2s
    assert cl.delete(f"/songs/{sid}").json()["ok"]
    assert cl.get(f"/songs/{sid}/chart").status_code == 404
    assert cl.get("/").status_code == 200
    print(f"api ok: song {sid}, {len(ch['chords'])} chord spans")


if __name__ == "__main__":
    test_url_keys()
    test_analysis_pipeline()
    test_capo_song()
    test_api_flow()
    print("ALL SERVER TESTS PASSED")
