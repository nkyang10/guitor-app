# 🎸 Guitar Trainer – 屋企電腦分析 server

手機 App 嘅「歌曲」功能可以喺手機本機分析（快、無需 server），但想要 **更準嘅和弦 / 分段**、**直接貼 YouTube 連結**、
同埋 **「冇結他」伴奏音軌（Demucs 分離）**，就要喺屋企部電腦行呢個 server。

```
手機 App ──(Wi-Fi / Tailscale)──▶ 電腦 server :8765
   貼 YouTube link / 分享      yt-dlp 下載 → ffmpeg → (Demucs 分離結他) → librosa 節拍 + 和弦 + 分段 + 節奏型
   ◀── chart.json + mix.m4a + no_guitar.m4a（App 會 cache 落手機，之後離線都用得）
```

## 1. 安裝（Windows 10/11）

1. 裝 **Python 3.11 或 3.12**：<https://www.python.org/downloads/> → 安裝時剔 **"Add python.exe to PATH"**。
2. 裝 **ffmpeg**：開「命令提示字元」(cmd) 打
   ```
   winget install Gyan.FFmpeg
   ```
   裝完 **關咗 cmd 再開過**，打 `ffmpeg -version` 見到版本號就 OK。
3. 將成個 `server` 資料夾放喺例如 `C:\guitar\server`，然後：
   ```
   cd C:\guitar\server
   py -3.12 -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements.txt
   ```
4. （可選，建議）裝 Demucs 做結他分離（約 2 GB）：
   ```
   pip install -r requirements-demucs.txt
   ```
   有 NVIDIA 顯示卡想快啲：先跟 <https://pytorch.org/get-started/locally/> 裝 CUDA 版 torch，再裝 demucs。
5. 開 server：
   ```
   .venv\Scripts\activate
   uvicorn app:app --host 0.0.0.0 --port 8765
   ```
   第一次 Windows 會彈「防火牆」視窗 → 剔 **私人網絡 (Private)** → 允許。
6. 搵電腦 IP：另開 cmd 打 `ipconfig`，睇 Wi-Fi 嗰個 **IPv4 位址**（例如 `192.168.1.20`）。
7. 電腦瀏覽器開 <http://localhost:8765> 會見到測試頁（可以直接貼 YouTube link 試）。

> 想開機自動行：將以下內容存做 `start_server.bat`，放入「啟動」資料夾（Win+R → `shell:startup`）：
> ```
> cd /d C:\guitar\server
> call .venv\Scripts\activate
> uvicorn app:app --host 0.0.0.0 --port 8765
> ```

## 2. 安裝（Mac）

```bash
brew install python@3.12 ffmpeg
cd ~/guitar/server
python3.12 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pip install -r requirements-demucs.txt        # 可選
uvicorn app:app --host 0.0.0.0 --port 8765
```
IP：系統設定 → Wi-Fi → 詳細資料（例如 `192.168.1.20`）。macOS 問「接受連入連線」→ 允許。

## 3. 手機設定

1. 手機同電腦 **同一個 Wi-Fi**。
2. App → **設定** → **PC 分析伺服器** 填 `http://192.168.1.20:8765`（換做你部電腦嘅 IP）→ **儲存並測試**，見到「✓ 已連線（v1.0.0…）」就得。
3. App → **歌曲** → **貼上 YouTube 連結** → （可剔「分離結他音軌（Demucs，較慢）」）→ **用 PC 分析**；或者喺 YouTube App 撳「分享」→ 揀 **Guitar Trainer**。
4. 分析完 App 會自動下載 chart + 音軌，之後喺手機離線都練得。

### 出街都想用（可選）
電腦同手機都裝 **Tailscale**（免費，<https://tailscale.com>），登入同一個帳戶，
App 設定入面嘅伺服器地址改用電腦嘅 Tailscale IP（`http://100.x.y.z:8765`）就得，唔使開 router port。
**唔好**將 8765 port 直接開放去互聯網（server 冇登入保護）。

## 4. 分析做咗乜

| 步驟 | 工具 | 備註 |
|---|---|---|
| 下載 | yt-dlp | 最長 15 分鐘；同一條片（唔同 link 格式）只會分析一次（cache） |
| 轉檔 | ffmpeg | mix.m4a（128 kbps AAC）+ 分析用 WAV |
| 分離（可選） | Demucs `htdemucs_6s --two-stems guitar` | 出 `no_guitar.m4a`（跟住彈用）；結他音軌用嚟認和弦/節奏，準確啲 |
| 節拍 | librosa onset + **metrical autocorrelation tempo**（避開 3/2、雙倍速錯誤）+ DP beat tracking | |
| 和弦 | beat-sync CQT chroma + bass chroma → 大/小/7/m7 template → Viterbi（小節頭先易轉和弦） | |
| Key / Capo | Krumhansl key；capo 建議同 App 一樣（Level 1 盡量開放和弦、避 barre） | |
| 分段 | checkerboard novelty（chroma + MFCC + 響度）→ 對齊小節頭 → 相似段落同一字母（段落 A/B…，（強）= 較大聲） | |
| 節奏型 | 每段 16 格 onset 向量 vs App 內置節奏型（按 level 過濾） | |

## 5. 測試

```bash
python tests/test_pipeline.py        # 合成歌曲：BPM、和弦、capo、分段、API 流程
```
（亦可以 `pytest tests/`）

## 6. 準確度評估（任務：20 首歌評估）

1. 複製 `eval_songs_template.csv` 做 `eval_songs.csv`，每行填一首歌：YouTube 連結、正確 BPM / Key / Capo、和弦序列（由和弦譜抄，空格分隔）。
2. `python eval_songs.py eval_songs.csv` → 生成 `eval_report.md`（BPM、Key、Capo、和弦詞彙 F1、和弦序列相似度）。
3. 每首歌再喺 App 跟住彈一次，喺 csv 嘅 `feel` 欄填 1–5 分（同步感 / 好唔好彈）。

## 7. 常見問題

- **YouTube 下載失敗**：YouTube 成日改，先 `pip install -U yt-dlp`。
- **Demucs 好慢**：CPU 版一首 4 分鐘歌大約 1–5 分鐘；可以喺 App 分析時唔剔「分離結他音軌」。
- **手機連唔到**：同一 Wi-Fi？IP 啱？防火牆允許咗？電腦瀏覽器開 `http://<IP>:8765/health` 試。
- **歌曲資料**：存喺 `server/data/songs/<id>/`（meta.json、chart.json、mix.m4a、no_guitar.m4a），可以直接刪。
- 只供個人練習用；請尊重版權同 YouTube 條款。

## 8. API

| Method | Path | 回應 |
|---|---|---|
| GET | `/health` | `{ok, version, demucs, ytdlp, ffmpeg}` |
| POST | `/analyze` `{url, separate, level, force}` | `{job_id}` |
| POST | `/analyze_file`（multipart：file, separate, level, title） | `{job_id}` |
| GET | `/jobs/{id}` | `{id, status: queued/running/done/error, progress, message, song_id, error}` |
| GET | `/songs` | `[{id, title, bpm, key, duration, has_no_guitar, source}]` |
| GET | `/songs/{id}/chart` | Chart JSON（同 App `music/Chart.kt` 一樣） |
| GET | `/songs/{id}/audio?stem=mix\|no_guitar` | audio/mp4（冇就 404） |
| DELETE | `/songs/{id}` | `{ok}` |
