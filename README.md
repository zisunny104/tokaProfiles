# tokaProfiles

玩家資料服務：以 UUID 為準保存每位玩家的名稱、歷史名稱、偏好與稱號，玩家可從 `/profiles` 開啟「城市通」查看自己的資料。

## 主要功能

- **城市通**：玩家輸入 `/profiles`，看到自己的名稱與識別碼；尚未有資料時會提示「尚未完成玩家資料搬遷」。
- **稱號**：管理員可為玩家設定稱號（可含空白與色碼，最長 255 字元）。稱號只用來顯示，不代表任何權限。
- **同名處理**：同名但 UUID 不同的玩家視為不同人；用名稱查詢時若對到多位，不會任選一位，請改用 UUID。
- **舊資料搬遷**：把舊系統匯出的玩家資料在背景分批搬進來，過程不影響目前的有效資料。

## 玩家怎麼用

| 指令 | 說明 |
| --- | --- |
| `/profiles`（別名 `/profile`） | 例：`/profiles`，開啟城市通查看自己的資料（遊戲內） |
| `/profiles help` | 顯示說明 |

（圖：城市通視窗，顯示玩家名稱與識別碼）

## 管理員怎麼用

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `/profiles title` | `/profiles title <玩家名稱或 UUID>` | 查看該玩家的稱號 | `tokaProfiles.title.admin` |
| `/profiles title` | `/profiles title <玩家名稱或 UUID> <稱號>` | 設定稱號 | `tokaProfiles.title.admin` |
| `/profiles title` | `/profiles title <玩家名稱或 UUID> clear` | 清除稱號 | `tokaProfiles.title.admin` |

`/profiles titles` 與 `/profiles title` 相同。`tokaProfiles.self`（開啟城市通）預設所有人，`tokaProfiles.title.admin` 預設 OP。

搬遷舊資料建議採用背景分批：先停服，把舊資料匯出的 JSON 放進插件資料夾，在 `config.yml` 設定 `staged.*` 後啟動，資料會搬進獨立資料集；驗證完成後，再同時將 `staged.activate-next-restart` 與 `staged.source-refreshed-after-stop` 設為 `true`，下次重啟即切換，並自動把這些開關改回 `false`。驗證失敗時會保留原本的有效資料。

## 常用設定（`config.yml`，修改後需重新啟動伺服器）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 訊息語言 |
| `storage.backend` | `sqlite` | `sqlite`、`json`、`yaml`；切換前必須先備份並搬遷，不能只改這裡 |
| `storage.queue-capacity` | `128` | 背景寫入佇列容量，滿了會拒絕寫入 |
| `storage.active-directory` | `storage` | 目前使用的資料目錄；切換資料集後會自動更新 |
| `staged.enabled` | `false` | 啟動時開始背景搬遷 |
| `staged.source` | `legacy-playerData.json` | 舊資料匯出檔（上限 64 MiB） |
| `staged.directory` | `staged-migration` | 搬遷資料集目錄，須與有效目錄互相獨立 |
| `staged.batch-size`、`staged.delay-ms` | `50`、`100` | 每批筆數與批次間隔（毫秒） |
| `staged.activate-next-restart` | `false` | 下次重啟切換到已驗證的新資料集（需同時確認來源已重新匯出） |
| `migration.enabled` | `false` | 舊的離線一次性匯入，需 `confirm` 與來源檔的 SHA-256 |

## 給其他插件的串接

以 `Main` 的 `profiles()` 取得玩家資料庫（尚未就緒時會丟出例外）。

| 方法 | 參數 | 用途 |
| --- | --- | --- |
| `find` | UUID | 以 UUID 取得玩家資料 |
| `findByName` | 名稱 | 以名稱取得資料；對到多位時回傳空 |
| `resolveName` | 名稱 | 取得符合名稱的所有 UUID |
| `snapshot` | 無 | 取得全部玩家資料 |
| `put` | `Profile` | 新增或更新玩家資料（非同步） |
| `updateTitle` | UUID、稱號 | 更新稱號（非同步） |
| `Main.openCityPass` | 玩家 | 對玩家開啟城市通 |

`Profile` 提供 `uuid()`、`name()`、`aliases()`、`preferences()`、`legacyId()`、`title()`。

## 常見問題

- **城市通顯示「尚未完成玩家資料搬遷」？** 該玩家的資料還沒有搬進來，請依上述流程搬遷。
- **用名稱設定稱號失敗？** 可能有多位玩家同名，請改用 UUID。

目前版本 0.2.1
