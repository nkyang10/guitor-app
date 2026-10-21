# 架構

> 版本 v0.2.0（UI 改版；核心模組同 v0.1.0 一樣）。數字來源：[validation/REPORT.md](../validation/REPORT.md)。

## 1. 總覽

```
[Android App]  即時練習：麥克風 → DSP → Judge → GameSession（分數）
               本機分析：音檔 → SongAnalyzer → Chart（離線可用）
      │  Wi-Fi / Tailscale（可選）
      ▼
[屋企 server]  YouTube link / 上傳 → yt-dlp → ffmpeg →（Demucs 分離）
               → analysis.py（節拍 + 和弦 + 分段 + 節奏型）→ chart.json
```

- 即時練習（調音、判定）全部喺手機本機完成，唔需要 server。
- Server 只係畀更準嘅歌曲分析，同「冇結他」伴奏音軌（Demucs）。
- 手機會 cache server 結果（chart.json、mix.m4a、no_guitar.m4a），之後離線都用得。

## 2. Android App

路徑：`android/app/src/main/java/com/boss/guitartrainer/`

| Package | 責任 | 主要檔案 |
|---|---|---|
| `audio/` | 麥克風 / 喇叭引擎、節拍機、即時分析 | `AudioEngine.kt`（UNPROCESSED 優先，唔支援就用 VOICE_RECOGNITION）、`MicSession.kt`、`AudioRing.kt`、`ClickTrack.kt`、`LiveAnalyzer.kt` |
| `dsp/` | 音高、onset、和弦 chroma、掃弦方向、Basic Pitch | `Pitch.kt`（MPM）、`OnsetDetector.kt`、`ChordAnalyzer.kt`、`DirectionModel.kt`、`BasicPitch.kt`（TFLite）、`Fft.kt` |
| `game/` | 判定、難度、遊戲 session、校準、成績 | `Judge.kt`、`Difficulty.kt`、`GameSession.kt`、`NoteVerifier.kt`、`Calibrator.kt`、`Summary.kt` |
| `music/` | 樂理、和弦庫、節奏型、Chart | `Chords.kt`、`ChordNames.kt`、`Theory.kt`、`Patterns.kt`、`Chart.kt`、`ChartBuilder.kt` |
| `analysis/` | 本機歌曲分析 | `AudioDecoder.kt`（MediaCodec）、`SongAnalyzer.kt` |
| `net/` | 連屋企 server | `ServerApi.kt` |
| `data/` | 本機儲存（JSON）、本地日期 / 連續日數 | `Store.kt`、`Days.kt` |
| `ui/` | Compose 畫面（設計見 [DESIGN.md](DESIGN.md)） | `Theme.kt`（色板 / 字體 / 元件）、`Navigation.kt`（4 個 tab）、`HomeScreen.kt`（今日）、`PracticeHubScreen.kt`（練習）、`StatsScreen.kt`（我的）、`TunerScreen.kt`、`MetronomeScreen.kt`、`PracticeScreen.kt`、`ChordScreens.kt`、`SongScreens.kt`、`GameScreen.kt`、`ResultsScreen.kt`、`CalibrationScreen.kt`、`SettingsScreen.kt` 等 |

## 3. Server

路徑：`server/`

| 檔案 | 責任 |
|---|---|
| `app.py` | FastAPI 入口：`GET /health`、`POST /analyze`（YouTube link）、`POST /analyze_file`（上傳）、`GET /jobs`、`GET /jobs/{id}`、`GET /songs`、`GET /songs/{id}/chart`、`GET /songs/{id}/audio?stem=mix\|no_guitar`、`DELETE /songs/{id}`、`GET /`（網頁） |
| `analysis.py` | ffmpeg 解碼 → 可選 Demucs → tempo（`estimate_tempo`）→ 和弦（chroma + Viterbi）→ key → capo → 分段（novelty）→ 節奏型 |
| `chordlib.py`、`chords.json` | 和弦庫 |
| `patterns.json` | 節奏型 |
| `eval_songs.py`、`eval_songs_template.csv` | 20 首歌評估（BPM、Key、Capo、和弦 F1 等） |
| `tests/` | `synth_song.py`（合成歌）、`test_pipeline.py`（端到端 + API 流程） |
| `data/`（運行時產生） | 歌曲同 job 數據；`GT_DATA` 環境變數可改位置；唔入 git |

- 單一 worker thread（`analysis-worker`）排隊處理分析 job。
- 同一條 YouTube 歌會得到同一個 song id（由 canonical URL 計出），已有 `chart.json` 就直接用快取，除非 `force`。

## 4. 數據流

### 4.1 即時練習
麥克風（UNPROCESSED 優先）→ DSP（MPM 音高、onset、和弦 chroma、掃弦方向）→ `Judge`（按 `Difficulty` 嘅窗口判定，先扣除手機延遲校準值）→ `GameSession` 累計成績 → `ResultsScreen`。

### 4.2 本機歌曲分析（手機）
音檔（`AudioDecoder`，MediaCodec）→ `SongAnalyzer`：tempo（metrical autocorrelation）→ beat-sync chroma + bass → 和弦（maj / min / 7 / m7 + Viterbi）→ key → capo → 分段 → `Chart`。

### 4.3 Server 分析
YouTube link / 上傳 → yt-dlp 下載 → ffmpeg 解碼 →（可選）Demucs `htdemucs_6s` 分離 → `analysis.py` → `chart.json` + `mix.m4a` + `no_guitar.m4a` → 手機 `ServerApi` 拎返 cache。

## 5. 關鍵參數（Phase 0 定案）

| 項目 | 定案 | 依據 |
|---|---|---|
| 音高 | MPM，clarity ≥ 0.8，5-frame median | REPORT §1 |
| Onset | log spectral flux，frame 1024 / hop 256，80–6000 Hz，最短 IOI 60 ms | REPORT §2 |
| 判定窗口 | Beginner：Perfect ±70 ms / Good ±140 ms；Intermediate：±40 / ±80 ms（再按 tempo 縮放） | REPORT §2 |
| 和弦門檻 | Beginner 0.60；Intermediate 0.65 | REPORT 結論一覽 |
| 掃弦方向 gate | `DIR_GATE` 0.75（約 60% 掃弦有判定，準確率 ~82%；唔判方向就唔扣分） | REPORT §4 |
| 指彈 | Basic Pitch threshold 0.40（DSP 做 fallback） | REPORT §5 |
| 歌曲 tempo | 120 BPM log-normal prior（σ 0.5 octave，權重 0.4），搜尋範圍 55–190 BPM | REPORT §6；`estimate_tempo()` |

## 6. 設計決定（摘要）

- **調音用 MPM，唔用 YIN**：合成手機情境 gross error 4%（YIN 11–12%）。
- **指彈用 Basic Pitch**：AUC 0.919，DSP（harmonic salience）0.842。
- **歌曲 tempo 改用 metrical autocorrelation**：舊法（librosa beat_track，prior 110）喺切分掃弦成日估成 3/2 或 2/3；GuitarSet 嘅 3/2、2/3 錯誤由 64 降到 20。
- **和弦「差一粒音」只做提示，唔扣分**（AUC 只有 0.64–0.69）。

## 7. 已知限制

- 掃弦方向：通用模型 balanced accuracy 74.6%，個人校準後 75.5%，所以用 gate 0.75 先判定。
- 驗證數據係 GuitarSet（鋼弦）+ 合成尼龍弦；**未有真實尼龍弦 + 手機錄音**，要靠 App 內嘅校準同測試畫面補。
- 歌曲分析未試過真 YouTube 歌。
- 和弦門檻、掃弦 gate 要用 1 星期實測（trial log）再調。
