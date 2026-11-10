# HuangGuoTV (黃果短劇 TV 版)

專為 Android TV 與智慧電視盒打造的輕量 H5 網頁容器應用。  
將短劇主站（`https://huangguoai.com`）封裝為適合電視大螢幕與遙控器操作的電視專用客戶端，具備**虛擬滑鼠游標**、**雲端網址熱更新（防失聯）**與**App 內 OTA 更新**。

---

## ✨ 核心特性

1. **極致輕量 & 極致相容**：
   - 移除官方龐大的 Flutter 引擎與冗餘動態庫，僅保留純 Android 原生 WebView 與視圖元件，APK 體積僅約 2MB。
   - 支援 `minSdk 21` (Android 5.0) 至最新 Android 系統。
   - 內建 `armeabi-v7a` 與 `arm64-v8a` ABI 過濾器，100% 相容 32 位元晶片與僅 1GB RAM 的低階老電視盒。

2. **電視大螢幕 & 遙控器深度適配**：
   - **直立短劇版面優化**：自動注入 CSS/JS，將網頁底色設為純黑 (`#000000`)、短劇播放器水平居中，並自動隱藏「下載 App」浮動橫幅。
   - **播放快捷模式**：
     - `DPAD_UP` / `DPAD_DOWN`（上/下鍵）：滾動或滑動切換短劇（上一集 / 下一集）。
     - `DPAD_CENTER` / `ENTER`（確認鍵）：自動尋找目前播放之 `<video>` 標籤切換播放 / 暫停。
     - `DPAD_LEFT` / `DPAD_RIGHT`（左/右鍵）：快退 / 快進 10 秒。
   - **虛擬滑鼠游標模式**：
     - 按遙控器「選單鍵 (`MENU`)」或連按兩下「返回鍵 (`BACK`)」即刻開啟虛擬滑鼠。
     - 畫面上顯示高辨識度圓形游標，遙控器方向鍵平滑移動游標，確認鍵觸發真實點擊，輕鬆點擊選集、搜尋與分類按鈕。

3. **雙層防失聯機制**：
   - **第一層：雲端配置熱更新 (`remote_config.json`)**：
     - App 啟動時自動從 GitHub Raw 檢查遠端配置並快取。
     - 若主站域名更動或需新增備援線路，只需修改 GitHub 倉庫上的 `remote_config.json`，所有使用者的電視重開即可自動載入新網址，免重裝 APK。
     - 長按確認鍵或選單鍵可呼出 TV 控制台，手動切換備用線路或切換手機版/桌面版 UA。
   - **第二層：App 內一鍵 OTA 更新**：
     - 串接 GitHub Releases API，當有新版本釋出時自動彈窗提示。
     - 內建下載進度條並透過 `FileProvider` 自動呼叫系統安裝器完成覆蓋升級。

---

## 🎮 遙控器按鍵對照表

| 遙控器按鍵 | 預設模式（播放快捷模式） | 虛擬滑鼠模式 |
| :--- | :--- | :--- |
| **上 / 下鍵** (`DPAD_UP / DOWN`) | 向上 / 向下滑動（切換上一集 / 下一集） | 平滑向上 / 向下移動游標 |
| **左 / 右鍵** (`DPAD_LEFT / RIGHT`) | 快退 10 秒 / 快進 10 秒 | 平滑向左 / 向右移動游標 |
| **確認鍵** (`DPAD_CENTER / ENTER`) | 切換影片播放 / 暫停 | 在游標所在座標觸發點擊 (Down + Up) |
| **長按確認鍵 / 選單鍵** | **呼出 TV 控制台選單** | **呼出 TV 控制台選單** |
| **選單鍵** (`MENU`) | 開啟虛擬滑鼠模式 | 關閉虛擬滑鼠模式 |
| **雙擊返回鍵** (`BACK`) | 切換開啟虛擬滑鼠模式 | 切換關閉虛擬滑鼠模式 |
| **單擊返回鍵** (`BACK`) | 網頁後退 / 提示再按一次退出 | 關閉虛擬滑鼠模式 |

---

## 🛠️ TV 控制台功能

長按遙控器「確認鍵」1 秒即可呼出 TV 控制台面板：
- **切換虛擬滑鼠**：一鍵開關游標。
- **切換播放線路**：在主站域名與備用集群線路間自由切換。
- **強制重載雲端配置**：即時自 GitHub 遠端拉取最新線路與 CSS/JS 規則。
- **切換 User-Agent**：支援切換「手機版 H5 版面」與「桌面版版面」。
- **清除快取**：清理 WebView 快取並強制重刷頁面。
- **檢查版本更新**：手動檢查 GitHub Releases 最新 APK。

---

## 📦 專案結構

```text
HuangGuoTV/
├── .github/workflows/
│   └── build_apk.yml            # GitHub Actions 自動建置與 Release 工作流
├── app/
│   ├── build.gradle.kts         # 模組建置配置 (minSdk 21, abiFilters)
│   ├── src/main/
│   │   ├── AndroidManifest.xml  # TV Leanback 宣告與權限配置
│   │   ├── assets/
│   │   │   └── remote_config.json # 內建離線備援雲端配置
│   │   ├── java/com/huangguo/tv/
│   │   │   ├── HuangGuoApp.kt   # 全域 Application 與未捕獲異常處理
│   │   │   ├── MainActivity.kt  # 核心全螢幕 WebView 與遙控器映射
│   │   │   ├── config/
│   │   │   │   ├── ConfigManager.kt # 雲端熱更新與快取排程
│   │   │   │   └── RemoteConfig.kt  # 配置資料模型
│   │   │   ├── update/
│   │   │   │   └── UpdateManager.kt # GitHub Releases OTA 自動更新
│   │   │   └── view/
│   │   │       └── CursorLayout.kt  # 電視虛擬滑鼠游標 ViewGroup
│   │   └── res/                 # 遙控器焦點樣式、向量圖示與版面配置
├── remote_config.json           # 根目錄熱更新配置檔 (推送至 GitHub 即生效)
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 🚀 建置與發布

### 方式 1：使用 GitHub Actions 自動編譯（推薦）
1. 將專案推送至 GitHub。
2. 建立標籤並推送（例如 `git tag v1.0.0 && git push origin v1.0.0`）。
3. GitHub Actions 會自動完成編譯、簽名，並在 Releases 頁面發布 `HuangGuoTV-Release.apk`。

### 方式 2：本機編譯
需要本機安裝 JDK 17+ 與 Android SDK：
```bash
./gradlew assembleRelease
```
編譯產物將輸出於：`app/build/outputs/apk/release/`
