"""Home-PC analysis server for the classical-guitar trainer app.

Run:  uvicorn app:app --host 0.0.0.0 --port 8765
The phone app (設定 → Server URL, e.g. http://192.168.1.20:8765) talks to these endpoints:

  GET    /health                         -> {ok, version, demucs, ytdlp, ffmpeg}
  POST   /analyze      {url, separate, level, force}  -> {job_id}
  POST   /analyze_file (multipart: file, separate, level, title) -> {job_id}
  GET    /jobs/{id}                      -> {id, status: queued|running|done|error, progress, message, song_id, error}
  GET    /jobs                           -> recent jobs
  GET    /songs                          -> [{id, title, bpm, key, duration, has_no_guitar, source}]
  GET    /songs/{id}/chart               -> Chart JSON (same schema as the app)
  GET    /songs/{id}/audio?stem=mix|no_guitar  -> audio/mp4 (404 when missing)
  DELETE /songs/{id}
  GET    /                               -> tiny web page for testing from the PC browser
"""
import hashlib
import json
import os
import queue
import re
import shutil
import tempfile
import threading
import time
import traceback
import uuid
from typing import Optional
from urllib.parse import parse_qs, urlparse

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel

import analysis

VERSION = "1.0.0"
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GT_DATA", os.path.join(HERE, "data"))
SONGS = os.path.join(DATA, "songs")
os.makedirs(SONGS, exist_ok=True)

app = FastAPI(title="Guitar Trainer analysis server", version=VERSION)

JOBS = {}
LOCK = threading.Lock()
QUEUE = queue.Queue()
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")


class AnalyzeReq(BaseModel):
    url: str
    separate: bool = True
    level: int = 1
    force: bool = False


# ------------------------------------------------------------------ helpers
def canonical_url(url: str) -> str:
    """YouTube links in all their forms map to one key so the same song is analysed once."""
    url = url.strip()
    m = re.search(r"https?://\S+", url)          # shared text often contains a title before the link
    if m:
        url = m.group(0)
    u = urlparse(url)
    host = (u.netloc or "").lower()
    vid = None
    if host.endswith("youtu.be"):
        vid = u.path.strip("/").split("/")[0]
    elif "youtube.com" in host:
        if u.path.startswith("/watch"):
            vid = (parse_qs(u.query).get("v") or [None])[0]
        else:
            m2 = re.match(r"^/(shorts|live|embed)/([^/?#]+)", u.path)
            if m2:
                vid = m2.group(2)
    if vid:
        return "youtube:" + vid
    return url


def clean_url(url: str) -> str:
    m = re.search(r"https?://\S+", url.strip())
    return m.group(0) if m else url.strip()


def song_id_for(key: str) -> str:
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:10]


def song_dir(sid: str) -> str:
    if not ID_RE.match(sid):
        raise HTTPException(400, "bad id")
    return os.path.join(SONGS, sid)


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)


def set_job(job_id, **kw):
    with LOCK:
        JOBS[job_id].update(kw)
        JOBS[job_id]["updated"] = time.time()


def new_job(kind, **kw):
    job_id = uuid.uuid4().hex[:12]
    with LOCK:
        JOBS[job_id] = dict(id=job_id, kind=kind, status="queued", progress=0.0, message="排緊隊…",
                            song_id=None, error=None, created=time.time(), updated=time.time(), **kw)
        # keep memory bounded
        if len(JOBS) > 200:
            for old in sorted(JOBS.values(), key=lambda j: j["created"])[:50]:
                if old["status"] in ("done", "error"):
                    JOBS.pop(old["id"], None)
    QUEUE.put(job_id)
    return job_id


def public_job(j):
    return {k: j.get(k) for k in ("id", "status", "progress", "message", "song_id", "error")}


def finish_song(sid, chart, has_ng, title, source, level, separate):
    d = os.path.join(SONGS, sid)
    chart["id"] = sid
    chart["title"] = title
    write_json(os.path.join(d, "chart.json"), chart)
    dur = float(chart.get("analysis", {}).get("duration") or (chart["beats"][-1] if chart.get("beats") else 0.0))
    meta = dict(id=sid, title=title, bpm=chart["bpm"], key=chart.get("key"), duration=round(dur, 2),
                has_no_guitar=bool(has_ng), source=source, level=level, separate=separate,
                created=time.strftime("%Y-%m-%d %H:%M:%S"), version=VERSION)
    write_json(os.path.join(d, "meta.json"), meta)
    return meta


def run_job(job):
    job_id = job["id"]

    def progress(p, msg):
        set_job(job_id, progress=round(float(min(max(p, 0.0), 0.99)), 3), message=msg)

    set_job(job_id, status="running", message="開始…", progress=0.01)
    level = int(job.get("level") or 1)
    separate = bool(job.get("separate"))
    if job["kind"] == "url":
        url = clean_url(job["url"])
        sid = song_id_for(canonical_url(url))
        d = os.path.join(SONGS, sid)
        if os.path.exists(os.path.join(d, "chart.json")) and not job.get("force"):
            set_job(job_id, status="done", progress=1.0, message="之前分析過（用緊快取）", song_id=sid)
            return
        os.makedirs(d, exist_ok=True)
        work = tempfile.mkdtemp(prefix="dl_", dir=d)
        try:
            progress(0.02, "下載緊 YouTube 音訊…")
            path, title = analysis.download(url, work, progress)
            chart, has_ng = analysis.analyze_file(path, title, url, d, level, separate, progress)
            finish_song(sid, chart, has_ng, title, url, level, separate)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    else:
        path = job["path"]
        sid = job["sid"]
        d = os.path.join(SONGS, sid)
        if os.path.exists(os.path.join(d, "chart.json")) and not job.get("force"):
            shutil.rmtree(os.path.dirname(path), ignore_errors=True)
            set_job(job_id, status="done", progress=1.0, message="之前分析過（用緊快取）", song_id=sid)
            return
        os.makedirs(d, exist_ok=True)
        try:
            chart, has_ng = analysis.analyze_file(path, job["title"], "file:" + job["filename"], d, level, separate, progress)
            finish_song(sid, chart, has_ng, job["title"], "file:" + job["filename"], level, separate)
        finally:
            shutil.rmtree(os.path.dirname(path), ignore_errors=True)
    set_job(job_id, status="done", progress=1.0, message="完成", song_id=sid)


def worker():
    while True:
        job_id = QUEUE.get()
        with LOCK:
            job = dict(JOBS.get(job_id) or {})
        if not job:
            continue
        try:
            run_job(job)
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            msg = str(e) or e.__class__.__name__
            set_job(job_id, status="error", error=msg[:500], message="分析失敗：" + msg[:120])
        finally:
            QUEUE.task_done()


threading.Thread(target=worker, daemon=True, name="analysis-worker").start()


# ------------------------------------------------------------------ endpoints
@app.get("/health")
def health():
    try:
        import yt_dlp  # noqa: F401
        ytdlp = True
    except Exception:
        ytdlp = False
    return dict(ok=True, version=VERSION, demucs=analysis.have_demucs(), ytdlp=ytdlp, ffmpeg=analysis.have_ffmpeg(),
                queued=QUEUE.qsize())


@app.post("/analyze")
def analyze(req: AnalyzeReq):
    url = clean_url(req.url)
    if not url.startswith("http"):
        raise HTTPException(400, "唔係有效連結")
    job_id = new_job("url", url=url, separate=req.separate, level=max(1, min(3, req.level)), force=req.force)
    return {"job_id": job_id}


@app.post("/analyze_file")
async def analyze_file(file: UploadFile = File(...), separate: bool = Form(True), level: int = Form(1),
                       title: Optional[str] = Form(None), force: bool = Form(False)):
    tmpdir = tempfile.mkdtemp(prefix="up_", dir=DATA)
    name = os.path.basename(file.filename or "upload.bin")
    path = os.path.join(tmpdir, "src" + (os.path.splitext(name)[1] or ".bin"))
    h = hashlib.sha1()
    with open(path, "wb") as out:
        while True:
            chunk = await file.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            out.write(chunk)
    sid = "f" + h.hexdigest()[:9]
    os.makedirs(os.path.join(SONGS, sid), exist_ok=True)
    job_id = new_job("file", path=path, sid=sid, filename=name, title=title or os.path.splitext(name)[0],
                     separate=separate, level=max(1, min(3, level)), force=force)
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
def job(job_id: str):
    with LOCK:
        j = JOBS.get(job_id)
        if not j:
            raise HTTPException(404, "冇呢個 job")
        return public_job(j)


@app.get("/jobs")
def jobs():
    with LOCK:
        return [public_job(j) for j in sorted(JOBS.values(), key=lambda j: -j["created"])[:30]]


@app.get("/songs")
def songs():
    out = []
    for sid in os.listdir(SONGS):
        mp = os.path.join(SONGS, sid, "meta.json")
        if os.path.exists(mp):
            try:
                m = read_json(mp)
                out.append({k: m.get(k) for k in ("id", "title", "bpm", "key", "duration", "has_no_guitar", "source", "created")})
            except Exception:
                pass
    out.sort(key=lambda m: m.get("created") or "", reverse=True)
    return out


@app.get("/songs/{sid}/chart")
def chart(sid: str):
    p = os.path.join(song_dir(sid), "chart.json")
    if not os.path.exists(p):
        raise HTTPException(404, "未有分析結果")
    return read_json(p)


@app.get("/songs/{sid}/audio")
def audio(sid: str, stem: str = "mix"):
    if stem not in ("mix", "no_guitar"):
        raise HTTPException(400, "stem 要係 mix 或 no_guitar")
    d = song_dir(sid)
    p = os.path.join(d, stem + ".m4a")
    if not os.path.exists(p):
        alts = [f for f in (os.listdir(d) if os.path.isdir(d) else []) if f.startswith(stem + ".")]
        if not alts:
            raise HTTPException(404, "冇呢條音軌")
        p = os.path.join(d, alts[0])
    media = "audio/mp4" if p.endswith(".m4a") else "application/octet-stream"
    return FileResponse(p, media_type=media, filename=f"{sid}_{stem}{os.path.splitext(p)[1]}")


@app.delete("/songs/{sid}")
def delete(sid: str):
    d = song_dir(sid)
    if not os.path.isdir(d):
        raise HTTPException(404, "冇呢首歌")
    shutil.rmtree(d, ignore_errors=True)
    return {"ok": True}


PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Guitar Trainer server</title><style>body{font-family:sans-serif;max-width:720px;margin:24px auto;padding:0 12px}
input,button{font-size:16px;padding:6px}#u{width:70%}td,th{padding:4px 8px;border-bottom:1px solid #ddd;text-align:left}</style></head>
<body><h2>🎸 Guitar Trainer 分析 server</h2><p id="h">檢查緊…</p>
<p><input id="u" placeholder="貼 YouTube 連結"><button onclick="go()">分析</button>
<label><input type="checkbox" id="s" checked>分離結他音軌（要 Demucs）</label></p><p id="j"></p>
<h3>歌曲</h3><table id="t"></table>
<script>
async function j(u,o){const r=await fetch(u,o);return r.json()}
async function health(){const h=await j('/health');document.getElementById('h').textContent=
 `✓ v${h.version} · ffmpeg ${h.ffmpeg?'✓':'✗'} · yt-dlp ${h.ytdlp?'✓':'✗'} · Demucs ${h.demucs?'✓':'✗（冇分離音軌）'}`}
async function list(){const s=await j('/songs');document.getElementById('t').innerHTML='<tr><th>歌名</th><th>BPM</th><th>Key</th><th>長度</th><th>無結他音軌</th></tr>'+
 s.map(x=>`<tr><td>${x.title}</td><td>${Math.round(x.bpm)}</td><td>${x.key||''}</td><td>${Math.round(x.duration)}s</td><td>${x.has_no_guitar?'✓':''}</td></tr>`).join('')}
async function go(){const r=await j('/analyze',{method:'POST',headers:{'Content-Type':'application/json'},
 body:JSON.stringify({url:document.getElementById('u').value,separate:document.getElementById('s').checked,level:1})});poll(r.job_id)}
async function poll(id){const s=await j('/jobs/'+id);document.getElementById('j').textContent=`${s.status} ${Math.round(s.progress*100)}% ${s.message}`;
 if(s.status==='queued'||s.status==='running')setTimeout(()=>poll(id),1500);else list()}
health();list();
</script></body></html>"""


@app.get("/", response_class=HTMLResponse)
def index():
    return PAGE


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8765")))
