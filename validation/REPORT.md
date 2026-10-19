# Phase 0 驗證報告 – 古典結他練習 App

> 目的：喺寫 App 之前，用數據確認「手機咪 + DSP」做唔做到調音、拍子判定、和弦判定、掃弦方向、指彈音符驗證、歌曲分析。
> 數據：**GuitarSet**（360 段真結他錄音，hexaphonic 標註；注意係 **鋼弦**）+ **合成尼龍弦**（Karplus-Strong + 琴身 IR + 手機咪模擬：高通 120 Hz、房間殘響、雜訊）。
> **限制**：未用你部手機 + 你支尼龍結他錄過音 → 任務「錄音測試」「手機延遲」「指彈準確度」要你親手做（App 內建校準/測試畫面）。

## 結論一覽

| 功能 | 方法（最終揀） | 主要數字 | 決定 |
|---|---|---|---|
| 調音器 | **MPM** + clarity ≥ 0.8 + 5-frame median | 合成手機：gross error 4%（YIN 11–12%），p95 4.6 cents；GuitarSet 1607 音：median 3.5 c、p90 10 c、gross 7.9%（YIN 4.1 c / 10.3%） | ✅ 用 MPM |
| 拍子 / onset | log spectral flux（λ=1, lag 2, 80–6000 Hz, 1024/256）+ 自適應門檻（median 24 × 1.5 + mean 280）+ 最短 IOI 60 ms；有譜時 guided（弱 onset ≥ 0.7 都收） | 合成掃弦 recall 0.88 → **0.99（guided）**；GuitarSet comp 0.90 → **0.967**，solo 0.98 → 0.99；遠距離誤報 27–33/分鐘（有譜時唔計）；timing SD ≈ 18–22 ms | ✅ |
| 和弦判定 | 期望音 chroma 相似度 + 每粒音 presence（20 dB） | GuitarSet：thr 0.60 → 對嘅和弦通過 95.4%、錯和弦誤收 18.2%；0.65 → 87.4% / 10.5%；AUC（錯和弦）0.95–0.96；差一粒音 AUC 只有 0.64–0.69；悶音偵測 42–57% | ✅ Beginner 0.60、Intermediate 0.65；「差一粒音」只做提示唔扣分 |
| 掃弦方向（上/下） | 12-band IIR filterbank 303 features + logistic regression；**個人校準**（8 小節 D/U）MAP adaptation λ=30；信心 gating | 通用模型 LOPO balanced **74.6%**（v1 58%）；只用 32 個個人樣本 71.3%；adaptation **75.5%**；gating：conf ≥ 0.7 → 判 69%、準 80.9%；≥ 0.8 → 50% / 83.4% | ✅ DIR_GATE 0.75（約 60% 掃弦有判定，準 ~82%），其餘唔判方向唔扣分 |
| 指彈音符驗證 | Basic Pitch（TFLite）2 秒窗 vs DSP | AUC 0.919、balanced acc 0.884 @0.40（DSP 0.842）；30 ms / 2 秒窗 | ✅ 用 Basic Pitch，DSP 做 fallback |
| 歌曲節拍（server + 手機） | onset envelope → **metrical autocorrelation tempo**（beat + 半音符 + 小節 + 八分，log-normal prior 120 BPM σ 0.5 oct）→ DP beat tracking | GuitarSet comp：exact **53% → 61%**，含半/雙倍 **62% → 76%**；solo 67% → 75% / 76% → 86%；3/2、2/3 錯誤 64 → 20；合成歌 10/10 | ✅ Kotlin 版同 Python 結果一致（fixture 測試 diff 0） |
| 歌曲和弦 / capo / 分段 | beat-sync CQT chroma + bass → maj/min/7/m7 + Viterbi；Krumhansl + 和弦證據 key；capo 規則同 App；novelty（chroma+MFCC+響度）分段 | 合成歌：和弦 100%、capo（Bb F Gm Eb → capo 3）✓、主歌/副歌分界準確到小節；真歌未測 | ⏳ 要你提供 20 首歌做評估（eval_songs.py） |

## 細節

### 1. 調音器（eval_tuner.py, eval_tuner_post.py, eval_real.py）
- 合成手機情境（高通、殘響、雜訊 SNR 25–35 dB）：MPM gross 4%，YIN 11–12%。後處理 clarity 0.8 + median 5 → p95 4.6 cents。
- GuitarSet 真錄音 1607 粒單音：MPM median 3.5 c / p90 10 c / gross 7.9%；YIN 4.1 c / 10.3%。
- App：`dsp/Pitch.kt`（MPM），`ui/TunerScreen.kt`（ring buffer；±4 cents 綠燈「✓ 準！」，±15 cents 黃）。

### 2. Onset / 拍子（eval_onset.py, eval_real_onset2.py, sweep_real_onset.py, final_onset_check.py）
- 最終參數：frame 1024 / hop 256，log-flux λ=1、lag 2、80–6000 Hz；門檻 = median(24) × 1.5 + 1.0 × max(1, mean(280))；最短 IOI 60 ms；guided 模式弱 onset ≥ 0.7。
- 判定窗口（`game/Difficulty.kt`）：Beginner Perfect ±70 ms / Good ±140 ms；Intermediate ±40 / ±80 ms（再按 tempo 縮放），先扣除手機延遲校準值。

### 3. 和弦（eval_chord.py → results_chord.json，含 ROC）
- GuitarSet 模擬「彈錯和弦」：thr 0.60 TPR 95.4% / FPR 18.2%；0.65 TPR 87.4% / FPR 10.5%。合成：0.60 / 0.65 TPR 100%，FPR 43% / 30%（合成音色太乾淨，錯和弦更似）。
- Presence 20 dB：正確和弦每粒音通過 95–98%；悶咗嘅弦捉到 42–57% → 只做提示。

### 4. 掃弦方向（dir2.py, dir2feat.py, dir_export.py → results_direction_v2*.json）
- 6930 個 GuitarSet 掃弦（hexaphonic 標註計方向）。v1（簡單 spectral 特徵）只有 ~58% 且偏向「下」。
- v2：12 個 IIR band（每 band 能量包絡時間差 + 斜率）303 features + logistic regression。
- 個人校準：Calibration 畫面 60 BPM 彈 8 小節 D/U 八分音 → MAP adaptation（λ=30）。
- 資產：`assets/direction_generic.json`（44.1 / 48 kHz SOS 係數）；Kotlin 特徵同 Python 一致（`DirectionTest` fixture）。

### 5. 指彈（eval_basic_pitch.py）
- 「呢粒音有冇彈到」：Basic Pitch AUC 0.919、balanced 0.884（threshold 0.40）；DSP（harmonic salience）0.842。

### 6. 歌曲分析（server/analysis.py、App analysis/SongAnalyzer.kt、eval_tempo.py、tune_tempo.py）
- Tempo 舊法（librosa beat_track prior 110）喺切分音掃弦（D-DU-UDU）成日估成 3/2 或 2/3（附點四分音符）。新法用「節拍層級一致性」揀 tempo，再固定 tempo 做 DP。
- 參數喺 GuitarSet + 10 首合成歌掃描（11 × 4 × 4 × 3 組合），揀 w = (1, .5, .5, .5)、prior 120、σ 0.5、mix 0.4。
- Server 測試（tests/test_pipeline.py）：100 BPM 合成歌 → BPM 99.4、和弦 100%、capo 0、分段 A / B（強）界線準確；84 BPM 升 3 半音 → capo 3、和弦 100%；API 流程（上傳、job、songs、chart、audio、cache、delete）全過。

## 尚未驗證（要你做）
1. **錄音測試**：用你部手機 + 尼龍結他，跑 App「校準」同「和弦檢查」，睇調音器、onset、和弦數字（目標：同上表相差 < 10%）。
2. **手機延遲**：校準畫面自動（PING loopback）+ 手動（跟拍 16 下），記低數值。
3. **指彈準確度**：彈 3 個指彈練習，睇 Results 畫面嘅「音符命中率」同你自己感覺比較。
4. **20 首歌評估**：server/eval_songs.py（BPM、Key、Capo、和弦詞彙 F1、bigram F1、feel 分數）。

## 檔案
- 評估腳本：`validation/*.py`；結果：`validation/results_*.json`
- 數據集：GuitarSet（`/data/datasets/guitarset`，唔包喺壓縮檔）
