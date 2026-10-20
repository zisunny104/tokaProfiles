# tokaProfiles

玩家資料服務：以 UUID 為準保存每位玩家的名稱、歷史名稱、偏好與稱號，玩家可從 `/profiles` 開啟「城市通」查看自己的資料。

## 主要功能

- **城市通**：玩家輸入 `/profiles`，看到自己的名稱與識別碼；尚未有資料時會提示「尚未完成玩家資料搬遷」。
- **稱號**：管理員可設定玩家稱號，支援空白與色碼，最長 255 字元。稱號只用來顯示，不代表任何權限。
- **同名處理**：同名但 UUID 不同的玩家視為不同人；用名稱查詢時若對到多位，不會任選一位，請改用 UUID。
- **舊資料搬遷**：把舊系統匯出的玩家資料在背景分批搬進來，過程不影響目前的有效資料。

## 玩家指令

| 指令 | 說明 |
| --- | --- |
| `/profiles`、`/profile` | 開啟城市通查看自己的資料 |
| `/profiles help` | 顯示說明 |

## 管理指令

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `/profiles title` | `/profiles title <玩家名稱或 UUID>` | 查看該玩家的稱號 | `tokaProfiles.title.admin` |
| `/profiles title` | `/profiles title <玩家名稱或 UUID> <稱號>` | 設定稱號 | `tokaProfiles.title.admin` |
| `/profiles title` | `/profiles title <玩家名稱或 UUID> clear` | 清除稱號 | `tokaProfiles.title.admin` |

`/profiles titles` 與 `/profiles title` 相同。`tokaProfiles.self` 預設所有人，可開啟城市通；`tokaProfiles.title.admin` 預設 OP。

搬遷舊資料建議採用背景分批：先停服，把舊資料匯出的 JSON 放進插件資料夾，在 `config.yml` 設定 `staged.*` 後啟動，資料會搬進獨立資料集；驗證完成後，再同時將 `staged.activate-next-restart` 與 `staged.source-refreshed-after-stop` 設為 `true`，下次重啟即切換，並自動把這些開關改回 `false`。驗證失敗時會保留原本的有效資料。

## 設定 config.yml

變更後需重新啟動。

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
| `staged.plan-evidence` | 空白 | 選用的 Plan 身分證據 JSON 路徑，用於交叉核對 |
| `staged.source-refreshed-after-stop` | `false` | 確認已停服重新匯出來源，且與搬遷時一致；切換時必須為 `true` |
| `staged.activate-next-restart` | `false` | 下次重啟切換到已驗證的新資料集（需同時確認來源已重新匯出） |
| `migration.enabled` | `false` | 舊的離線一次性匯入，需 `confirm` 與來源檔的 SHA-256 |

## 串接

以插件 `Main` 實例的 `profiles()` 取得玩家資料庫，服務尚未就緒時會丟出例外。

| 方法 | 參數 | 用途 |
| --- | --- | --- |
| `find` | UUID | 以 UUID 取得玩家資料 |
| `findByName` | 名稱 | 以名稱取得資料；對到多位時回傳空 |
| `resolveName` | 名稱 | 取得符合名稱的所有 UUID |
| `snapshot` | 無 | 取得全部玩家資料 |
| `put` | `Profile` | 新增或更新玩家資料（非同步） |
| `updateTitle` | UUID、稱號 | 更新稱號（非同步） |
| `Main` 實例的 `openCityPass` | 玩家 | 對玩家開啟城市通 |

`Profile` 提供 `uuid()`、`profileId()`、`name()`、`aliases()`、`preferences()`、`legacyId()`、`title()`；`profileId()` 與 `uuid()` 相同。

## 疑難排解

- **城市通顯示「尚未完成玩家資料搬遷」？** 該玩家的資料還沒有搬進來，請依上述流程搬遷。
- **用名稱設定稱號失敗？** 可能有多位玩家同名，請改用 UUID。

### Plan 選用整合（0.2.3）

預設自動接入 Plan，提供 `exists`（玩家資料是否存在）、`title`（稱號）、`count`（全服正式資料筆數）；可用 `plan.fields` 選擇欄位或全部關閉。只讀目前啟用的 ProfileRepository，不讀 staging、搬遷名單、legacy ID、別名或玩家偏好，不作身分判定。Plan Provider 讀 volatile immutable index，不等待儲存、不讀世界或玩家物件。資料庫不就緒時回傳 NotReady，避免虛構空資料。

使用官方 Plan API5.7-R0.2 provided，核對 Plan5.8 build3638 支援 Builder API；未安裝/停用/能力不足時略過，Plan 啟用停用及插件卸載會註冊/清除整合。Plan 模式與欄位可用下列管理指令即時套用。語系欄位在 `plan`，走 tokaI18n。Plan 網頁閱覽權限及已保存資料由 Plan 管理；停用欄位或更改標籤不會自動刪除 Plan 的歷史資料。


### Plan 模式與管理指令

預設 `plan.mode: auto`、`plan.fields: all`。`auto` 偵測到可用 Plan 就啟用；`enable` 未就緒時提示；`disable` 一定不發布。all 僅包含本插件核准的摘要欄位，沒有憑證、Discord ID 或私人座標。

管理員可用 `/profiles plan status`、`/profiles plan mode <auto|enable|disable>`、`/profiles plan fields <all|none|欄位...>`；變更保存設定且即時套用，保留其他設定及註解。舊 `plan.enabled`／逐項布林仍相容，明確設定的新 mode／fields 優先。

API（主執行緒）：`planStatusJson()`、`configurePlan(String mode, String[] fields)`；null 表示該項沿用。
