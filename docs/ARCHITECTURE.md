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
| `dsp/` | 音高、onset、和弦 chroma、掃弦方向、Basic Pitch、resample | `Pitch.kt`（MPM）、`OnsetDetector.kt`、`ChordAnalyzer.kt`、`DirectionModel.kt`、`BasicPitch.kt`（TFLite）、`Fft.kt`、`StreamResampler.kt` |
| `game/` | 判定、難度、遊戲 session、校準、成績 | `Judge.kt`、`Difficulty.kt`、`GameSession.kt`、`NoteVerifier.kt`、`Calibrator.kt`、`Summary.kt` |
| `music/` | 樂理、和弦庫、節奏型、Chart | `Chords.kt`、`ChordNames.kt`、`Theory.kt`、`Patterns.kt`、`Chart.kt`、`ChartBuilder.kt`、`SongSetup.kt`（每首歌設定）、`PatternSuggest.kt`（4 級節奏型） |
| `analysis/` | 本機歌曲分析 | `AudioDecoder.kt`（MediaCodec，串流解碼）、`Pcm.kt`（PCM → mono）、`SongAnalyzer.kt`、`RhythmAnalyzer.kt`（節拍包絡 / beat tracking / 對拍） |
| `net/` | 連屋企 server | `ServerApi.kt` |
| `data/` | 本機儲存（JSON）、本地日期 / 連續日數、加歌 job、閃退紀錄 | `Store.kt`、`Days.kt`、`SongImporter.kt`、`CrashLog.kt` |
| `ui/` | Compose 畫面（設計見 [DESIGN.md](DESIGN.md)） | `Theme.kt`（色板 / 字體 / 元件）、`Navigation.kt`（4 個 tab）、`HomeScreen.kt`（今日）、`PracticeHubScreen.kt`（練習）、`StatsScreen.kt`（我的）、`TunerScreen.kt`、`MetronomeScreen.kt`、`PracticeScreen.kt`、`ChordScreens.kt`、`SongScreens.kt`（歌曲列表）、`SongDetail.kt`（歌曲頁）、`SongTools.kt`（換 Key / 節奏型 / 拍子工具）、`GameScreen.kt`、`ResultsScreen.kt`、`CalibrationScreen.kt`、`SettingsScreen.kt` 等 |

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

記憶體（v0.2.1）：`AudioDecoder` 每個 decoder block 即刻 downmix（`Pcm`）+ 串流 resample（`StreamResampler`，同舊 batch 版同一個 windowed-sinc kernel，預計好 phase table，快 ~20 倍）到 22.05 kHz，只保留結果（`FloatBuf`，按長度預先開好，唔使 copy）。實測：4 分鐘 44.1 kHz 歌 peak heap ~34 MB、10 分鐘 48 kHz ~64 MB（舊版 4 分鐘已經要 ~130 MB，10 分鐘 256 MB heap 都 OOM）。
`SongImporter`（app scope，一次一個 job）負責複製檔案 → 解碼 → 分析 → 儲存，畫面只係睇 `state`；轉 tab / 轉方向唔會斷，亦唔會同時跑兩個分析；可以取消；失敗會刪走複製咗嘅檔。
限制：最長 10 分鐘（之後嘅部分唔分析，`SongEntry.partial` → 歌曲頁有提示）、檔案 ≤ 300 MB、最短 10 秒、聽唔到和弦會拒絕。`CrashLog` 將上次閃退（stack trace + heap 數字 + 當時做緊乜）存喺 `files/crash_last.txt`，設定頁可以分享。

### 4.3 Server 分析
YouTube link / 上傳 → yt-dlp 下載 → ffmpeg 解碼 →（可選）Demucs `htdemucs_6s` 分離 → `analysis.py` → `chart.json` + `mix.m4a` + `no_guitar.m4a` → 手機 `ServerApi` 拎返 cache。

### 4.4 跟歌設定（v0.2.3）
`chart.json` 永遠唔改；用家嘅改動每首歌分開存喺 `songDir(id)`：`song_prefs.json`（`SongPrefs`：transpose、首歌一齊升降、capo、節奏型級數 / 類別、每段自選、offset）、`beat_grid.json`（`BeatGrid`，修正過嘅拍子）、`rhythm_env.bin`（`OnsetEnvelope` cache，3-band spectral flux 100 fps，10 分鐘約 0.5 MB，最多分析 15 分鐘）。「還原」會清走呢啲檔。
`SongSetup.effective()` 疊加次序：拍子格 → 換 Key（和弦 + key）→ capo（自己揀就跟自己，否則按新 Key 重新建議）→ `pitchShift`（首歌一齊升降，`GameSession` 用 `PlaybackParameters` 變調）→ offset。
`PatternSuggest`：每段 4 級（簡單 / 一般 / 原曲 / 進階）；「原曲」由 onset 包絡讀出每小節 16 格嘅重音，唔喺節奏型庫嘅會生成 `orig:…` id（唔寫入 patterns.json）。`ChartBuilder.song()` 逐小節放節奏型，唔會溢出去下一小節。
對拍：`RhythmAnalyzer` 做 tempo + DP beat tracking，第一次建好包絡後自動對準一次（明顯更啱先改，可以復原）；「試聽」= 首歌 + 嗒聲；跟彈時「拍子早咗 / 遲咗」±20 ms，完成時存返落首歌。

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
- 手機本機分析：最長 10 分鐘（長歌只分析頭 10 分鐘）、檔案 300 MB 以下、冇分離音軌；PC server 最長 15 分鐘。
- 和弦門檻、掃弦 gate 要用 1 星期實測（trial log）再調。
