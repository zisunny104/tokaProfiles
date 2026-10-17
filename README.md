# tokaProfiles

**tokaProfiles 0.2.1** 是 Nukkit 玩家身分、偏好與稱號服務：以 UUID 為主鍵保存名稱、歷史名稱與稱號，並提供背景分批資料搬遷。預設 SQLite，也支援 JSON／YAML。

## 安裝

- 需要 Java 21；已驗證 Nukkit PM1E 4511。
- 將 `tokaProfiles-0.2.1.jar` 放入 `plugins/`，重新啟動伺服器。
- 使用共用套件 tokaStorage 與 tokaI18n，皆已內嵌於 JAR。
- 其他插件以 `Main.profiles()` 取得 repository；`Profile` 含 uuid、name、aliases、preferences、legacyId、title。
- 同名不同 UUID 視為不同身分，名稱查詢遇歧義不任選；稱號只是顯示資料，不授權。

## 指令與權限

| 指令 | 用途 | 權限（預設） |
| --- | --- | --- |
| `/profiles`（別名 `/profile`） | 個人資料與城市通入口 | `tokaProfiles.self`（所有人） |
| `/profiles help` | 查看說明 | `tokaProfiles.self` |
| `/profiles title｜titles <玩家名稱或 UUID> [稱號｜clear]` | 查看或修改玩家稱號 | `tokaProfiles.title.admin`（OP） |

## 設定（`config.yml`）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 語言 |
| `storage.backend` | `sqlite` | `sqlite`、`json`、`yaml`；切換前須備份並驗證 |
| `storage.queue-capacity` | `128` | 背景寫入佇列容量 |
| `storage.active-directory` | `storage` | 目前有效的資料目錄 |
| `staged.enabled` | `false` | 啟動時開始背景搬遷 |
| `staged.source` | `legacy-playerData.json` | 來源快照（上限 64 MiB） |
| `staged.directory` | `staged-migration` | 搬遷資料集目錄 |
| `staged.plan-evidence` | `""` | Plan 身分證據，僅作交叉核對 |
| `staged.batch-size` / `delay-ms` | `50` / `100` | 每批筆數與間隔 |
| `staged.activate-next-restart` / `source-refreshed-after-stop` | `false` | 兩者皆為 true 才會在下次重啟切換資料集 |
| `migration.*` | `enabled: false` | 舊的離線一次性匯入，仍相容 |

搬遷流程：停服後重新匯出來源、以新 stage 重跑、處理衝突並讀回驗證，再啟用切換。過程不會自動合併或刪除衝突資料。

## 版本紀錄

- 0.2.1：文件整理。
- 0.2.0：所有玩家可見文字移入 tokaI18n `zh-TW` 語系檔。
- 0.1.4：稱號管理、Plan 名稱對照、tokaStorage 0.1.3。

授權：MIT。
