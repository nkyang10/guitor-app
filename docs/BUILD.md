# Build、安裝、測試

## 1. Android App（`android/`）

### 需要
- **JDK 17 或以上**（開發時用 JDK 21 build 過）
- **Android SDK**：compileSdk / targetSdk **35**；minSdk **26**（Android 8.0+）
- Gradle wrapper 會自動下載 **Gradle 8.10.2**；AGP 8.7.3、Kotlin 2.0.21（見 `android/build.gradle.kts`）
- 推薦用 Android Studio 開 `android/` 資料夾；命令列都得。

### Build 同測試
```bash
cd android
./gradlew assembleDebug        # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 34 個 unit tests + 37 個畫面截圖測試（Paparazzi，唔使手機）
                               # DecodeMemoryTest 會開一個 -Xmx128m 嘅 JVM，模擬 10 分鐘 48 kHz 立體聲歌由解碼到分析（~15 秒）
./gradlew :app:recordPaparazziDebug   # 重新影晒所有畫面 → app/src/test/snapshots/images/（唔入 git）
```
Windows 用 `gradlew.bat`。

### 裝落手機
- 將 `app-debug.apk` 傳去手機撳開安裝；或者用 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- 第一次要允許「安裝未知來源 App」。
- **簽名 key**：有 `android/keystore/debug.keystore` 就用佢（唔入 git，記得備份），冇就用 `~/.android/debug.keystore`。一直用同一條 key，新 APK 先可以直接覆蓋更新、保留練習紀錄；換咗 key 就要先刪舊 App（紀錄會清空）。v0.1.0 → v0.2.0 就係要先刪舊版。

## 2. 屋企電腦 server（`server/`）

### 需要
- **Python 3.11 或 3.12**
- **ffmpeg**（要喺 PATH 度搵到）
- （可選）**Demucs**：分離「冇結他」伴奏。下載約 2 GB（PyTorch）；有 NVIDIA GPU 可以先裝 CUDA 版 torch。

### 安裝同運行
```bash
cd server
python -m venv .venv
source .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install -r requirements.txt
pip install -r requirements-demucs.txt   # 可選

uvicorn app:app --host 0.0.0.0 --port 8765
# 或者：python app.py（用 PORT 環境變數改埠）
```
手機 App「設定 → Server URL」填 `http://<電腦 IP>:8765`。Windows / Mac 詳細步驟、防火牆、出街用 Tailscale 見 [server/README.md](../server/README.md)。

### 測試
```bash
cd server
python tests/test_pipeline.py        # 合成歌端到端 + API 流程
```

### 運行數據
- 歌曲同 job 數據預設放喺 `server/data/`（唔入 git）。想換位置就設 `GT_DATA` 環境變數。

## 3. Phase 0 驗證腳本（`validation/`）

- 腳本：`validation/*.py`；結果：`validation/results_*.json`；報告：[validation/REPORT.md](../validation/REPORT.md)。
- 數據集 **GuitarSet**（Xi et al. 2018，CC BY 4.0）唔包喺 repo。`validation/gs.py` 預設讀 `/data/datasets/guitarset`。
- ⚠️ 以下腳本寫死咗開發環境路徑（`/data/guitar/...` 或 `/data/datasets/guitarset`），喺其他位置行之前要改成你自己嘅路徑：
  - `gs.py`：GuitarSet 路徑
  - `dir_export.py`、`dir_fixture.py`：寫入 `android/app/src/...` 嘅資產 / fixture
  - `eval_tempo.py`、`tune_tempo.py`：`sys.path` 同 cache 路徑

## 4. 常見問題
- **`./gradlew: Permission denied`**（Mac / Linux）→ `chmod +x android/gradlew`。
- **手機連唔到 server** → 確認電腦同手機用同一個 Wi-Fi，防火牆允許 8765；出街用 Tailscale（見 server/README.md）。
