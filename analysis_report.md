# 黃果短劇 (HuangGuoTV) APK 逆向解包與架構探勘報告

**分析日期**：2026-11-06  
**目標 APK**：`hgdj_1.7.0_261105_7_ms_493_JxKJuTpe_20261105140834_6.apk`  
**檔案大小**：25,407,448 Bytes (~24.23 MB)  

---

## 1. APK 基本資訊與相容性分析

| 項目 | 逆向解析結果 | 分析與 TV 端移植影響 |
| :--- | :--- | :--- |
| **Package Name** | `vip.qfteu.sutdba` | 官方包名，多處 channel 與 provider 授權基底 |
| **Version Code / Name** | `170` / `1.7.0` | 2026 年 11 月最新官方版本 |
| **minSdkVersion** | **`24` (Android 7.0)** | 支援 Android 7.0 以上電視盒/電視，門檻相對寬鬆 |
| **targetSdkVersion** | **`36` (Android 16)** | 適配最新 Android 平台規範 |
| **compileSdkVersion** | `36` | 使用 Android 16 (API 36) 工具鏈編譯 |
| **建置工具鏈** | AGP `8.3.2`、Kotlin `1.9.22`、Gradle `8.4` | 標準現代 Android 建置環境 |
| **內部專案名稱** | `DX-161-hgdj` (Android 標識：`DX161-hgdj-Android`) | Jenkins CI/CD 專案路徑洩漏 |

---

## 2. CPU 架構與原生庫 (Native Libs) 探勘

經檢查 APK 根目錄下的 `lib/` 架構：
```text
lib/
└── arm64-v8a/
    ├── libapp.so          (Flutter AOT Dart 業務邏輯編譯二進位檔, 16.2 MB)
    ├── libflutter.so      (Flutter Engine 引擎, 8.4 MB)
    ├── libijkffmpeg.so    (Bilibili ijkplayer FFmpeg 多媒體解碼庫)
    ├── libijkplayer.so    (ijkplayer 播放控制核心)
    └── libijksdl.so       (ijkplayer 繪圖/音效輸出層)
```

> [!CAUTION]
> **關鍵相容性瓶頸**：
> 官方釋出的 APK **僅包含 `arm64-v8a`（64 位元）**，完全**沒有提供 `armeabi-v7a`（32 位元）** 的原生動態庫！  
> 這正是此 APK **完全無法在 32 位元 (armeabi-v7a) 晶片或 1GB RAM 舊款 Android TV 盒子上安裝或啟動** 的根本原因（系統會報 `INSTALL_FAILED_NO_MATCHING_ABIS`）。

---

## 3. 開發框架與核心技術棧

1. **前端主框架：Flutter (Dart AOT)**
   - 採用 **Flutter Embedding V2** 架構。
   - 主 Activity：`com.org.aksm.MainActivity` 繼承自 FlutterActivity。
   - Impeller 狀態：`io.flutter.embedding.android.EnableImpeller = false`（關閉 Impeller，採用傳統 Skia 渲染）。
   - 本地儲存：採用 Flutter 的 `Hive` NoSQL Key-Value 資料庫。
2. **多媒體播放架構**：
   - 同時整合了 **ijkplayer (FFmpeg)** 與 **AndroidX Media3 / ExoPlayer**。
   - 串流協定採用 **HLS (HTTP Live Streaming, .m3u8)**。
   - 支援 `#EXT-X-KEY:METHOD=AES-128` 串流切片加密。
3. **原生與三方外掛**：
   - 包含 `just_audio`、`wakelock_plus`、`screen_brightness`、`image_picker`、`webview_flutter`、`profileinstaller` 等。

---

## 4. 網路架構、主站域名與備援機制

### 4.1 主站與備用 API 伺服器清單

實測結果（經實際發送 HTTP POST 探測，所有主備域名皆正常在線提供服務）：

| 類型 | 網址 | 實測狀態 (2026-11) | 說明 |
| :--- | :--- | :--- | :--- |
| **主站 API 域名** | `https://api1.huangguoju.com` | **HTTP 200 (在線)** | 官方首選主要業務域名 |
| **備用 API 伺服器 1** | `https://api1.wtzxzltu.cc` | **HTTP 200 (在線)** | 官方一級備用集群 |
| **備用 API 伺服器 2** | `https://api2.wtzxzltu.cc` | **HTTP 200 (在線)** | 官方二級備用集群 |
| **備用 API 伺服器 3** | `https://api3.wtzxzltu.cc` | 備用伺服器 | 負載均衡節點 |
| **備用 API 伺服器 4** | `https://api4.wtzxzltu.cc` | 備用伺服器 | 負載均衡節點 |
| **遠端緊急降級域名** | `https://bak.wtzxzltu.cc` | **HTTP 200 (在線)** | 由 GitHub 遠端動態下發之備援網址 |

### 4.2 動態遠端域名更新機制 (Remote Domain Fallback)

官方在 `libapp.so` 內嵌了 GitHub 遠端即時域名調度位址：
- **GitHub 降級配置 URL**：  
  `https://raw.githubusercontent.com/ailiu258099-blip/master/refs/heads/main/dx-161.txt`
- **當前解析結果**：該檔案內容直接指向 `https://bak.wtzxzltu.cc`。  
  *（這意味著若主域名遭 DNS 污染或封鎖，客戶端會動態請求此 GitHub raw 檔案取得最新的可用 API 伺服器）*

### 4.3 內建 DoH (DNS over HTTPS) 防劫持節點

APK 內部整合了 5 組公共 DoH 解析伺服器，用於在網路受阻時直接解析 API 域名 IP：
1. **Cloudflare**：`https://1.1.1.1/dns-query?type=A&name=`
2. **Google**：`https://8.8.8.8/resolve?type=A&name=`
3. **阿里雲 (AliDNS)**：`https://223.5.5.5/resolve?type=A&name=`
4. **騰訊 DNSPod**：`https://1.12.12.12/dns-query?type=A&name=`
5. **百度 DNS**：`https://120.53.53.53/dns-query?type=A&name=`

### 4.4 其他發現之 H5 / 活動 / 資源域名

- `https://ai-drama.com/` (AI 相關功能宣傳/資源)
- `playback.vip` (播放授權相關)
- `http(s)://new.ezowwn.cn/`、`http(s)://new.poemts.cn/`、`https://new.xmbvxj.cn/` (宣傳推廣與 H5 跳轉跳板，部分目前已停止解析)

---

## 5. API 路由與通訊協議結構

### 5.1 路由前綴規格

> [!IMPORTANT]
> **API 路徑前綴**：
> 所有業務介面均**必須加上 `/api/v1` 前綴**（若直接調用根路徑會回傳 `502 Bad Gateway`）。  
> 請求方法：所有資料接口**強制要求使用 `POST`**（使用 `GET` 會回傳 400 提示 `該接口只支持 POST`）。

### 5.2 核心通訊封裝 (Envelope Format)

所有 POST 回應均包裹在一層加密安全封裝中：
```json
{
  "errcode": 0,
  "data": "CWjIcGkbgZci+zrAuvVTXA/vx2vY239VMJtPKfGJNEAGwi/dMy7da8loD+YBTlgZxdkiGab0n...",
  "sign": "38fb8f2ef4d57fce7eb655b8fbfa1338",
  "timestamp": 1793933378
}
```

- **加密特徵**：
  - 加密模式為 **AES-CBC**。
  - 密文 Base64 前 64 字元（前 48 位元組，即 3 個 16-byte AES 區塊）在多個不同接口的回應中**完全相同**，證實採用了**靜態固定 Key 與靜態固定 IV**。
  - `sign` 為 32 字元的 MD5 雜湊校驗碼。
- **客戶端請求標頭 (HTTP Headers)**：
  - `x-auth`: 使用者憑證 / Token
  - `user-agent`: 客戶端標識
  - `channel`: `DX161-hgdj-Android`

---

## 6. 核心業務 API 端點分類清單

在 `libapp.so` 提取出超過 200 個功能端點，以下整理出開發 TV 端最關鍵的端點：

### 6.1 系統與配置 (System & Config)
- `/api/v1/app/config` 或 `/api/v1/content/common/rank-conf`：取得全域設定、開關、公告、版本資訊
- `/api/v1/system/system/download`：客戶端更新下載

### 6.2 短劇核心瀏覽 (Short Drama Browsing)
- `/api/v1/content/short-drama/home`：短劇首頁聚合內容（熱門推薦、分類精選）
- `/api/v1/content/short-drama/theater/feed`：劇場 Feed 流短劇列表
- `/api/v1/content/short-drama/theater/discovery`：短劇發現頁 / 探索清單
- `/api/v1/content/short-drama/theater/config`：劇場分類與過濾標籤設定
- `/api/v1/content/short-drama/rank`：短劇熱播榜單
- `/api/v1/content/short-drama/new`：最新上架短劇
- `/api/v1/content/short-drama/related`：關聯短劇推薦
- `/api/v1/content/short-drama/search`：短劇搜尋
- `/api/v1/content/short-drama/search-config`：搜尋熱搜詞、熱搜排行設定

### 6.3 影片與集數播放 (Video & Episodes Playback)
- `/api/v1/content/videos/detail`：短劇/影片詳情資訊
- `/api/v1/content/videos/episodes-list`：**集數列表（取得每一集的標題、第幾集、鎖定狀態等）**
- `/api/v1/content/videos/episode-detail`：**單集播放詳情（取得 m3u8 播放串流網址）**
- `/api/v1/home/com_video_url`：影片播放網址解析接口
- `/api/v1/content/videos/play-stats`：播放進度與統計上報

### 6.4 使用者帳號與歷史 (User & History)
- `/api/v1/account/users/login`：登入
- `/api/v1/account/users/register`：註冊
- `/api/v1/account/users/base-info`：個人基本資訊
- `/api/v1/content/short-drama/history/list`：短劇觀看歷史列表
- `/api/v1/content/short-drama/history/clear`：清除觀看歷史
- `/api/v1/account/favorites/short-drama-favorite`：短劇收藏
- `/api/v1/account/favorites/short-drama-like-list`：點讚短劇列表

---

## 7. 針對 32 位元 (armeabi-v7a) 1GB RAM Android TV 的移植結論

1. **不能直接沿用原版 APK**：  
   官方 APK 不包含 32 位元 `.so` 檔案，且官方 Flutter + ijkplayer 記憶體開銷大於 300MB，在 1GB RAM 的 Android TV 上啟動極易觸發 OOM 或卡頓。
2. **最佳建議方案（階段二實作路線）**：  
   - 使用 **原生 Android (Kotlin / Java)** 開發輕量級 Android TV 專用 App。
   - 採用 **Leanback 原生架構** 或 **極簡 Jetpack Compose for TV**。
   - 底層播放器採用輕量級 **AndroidX Media3 (ExoPlayer)**，編譯時納入 `armeabi-v7a` 與 `arm64-v8a`，並設定記憶體快取上限適配 1GB RAM。
   - 直接對接上述探勘出的 `/api/v1` API 接口，實現免廣告、全遙控器操作、極致流暢的短劇大螢幕體驗。
