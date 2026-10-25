# tokaProfiles

玩家資料服務：以 UUID 為準保存每位玩家的名稱、歷史名稱、偏好與稱號，玩家可從 `/profile` 開啟「城市通」查看自己的資料。

## 主要功能

- **城市通**：玩家輸入 `/profile`，看到自己的名稱與識別碼；尚未有資料時會提示「尚未完成玩家資料搬遷」。
- **稱號**：管理員可設定玩家稱號，支援空白與色碼，最長 255 字元。稱號只用來顯示，不代表任何權限。
- **同名處理**：同名但 UUID 不同的玩家視為不同人；用名稱查詢時若對到多位，不會任選一位，請改用 UUID。

## 玩家指令

| 指令 | 說明 |
| --- | --- |
| `/profile`、`/profile` | 開啟城市通查看自己的資料 |
| `/profile help` | 顯示說明 |

## 管理指令

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `/profile title` | `/profile title <玩家名稱或 UUID>` | 查看該玩家的稱號 | `tokaProfiles.title.admin` |
| `/profile title` | `/profile title <玩家名稱或 UUID> <稱號>` | 設定稱號 | `tokaProfiles.title.admin` |
| `/profile title` | `/profile title <玩家名稱或 UUID> clear` | 清除稱號 | `tokaProfiles.title.admin` |

`/profile titles` 與 `/profile title` 相同。`tokaProfiles.self` 預設所有人，可開啟城市通；`tokaProfiles.title.admin` 預設 OP。


## 設定 config.yml

變更後需重新啟動。

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 訊息語言 |
| `storage.backend` | `sqlite` | `sqlite`、`json`、`yaml`；切換後端不會自動搬移既有資料，不能只改這裡 |
| `storage.queue-capacity` | `128` | 背景寫入佇列容量，滿了會拒絕寫入 |
| `storage.active-directory` | `storage` | 目前使用的資料目錄 |

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

- **用名稱設定稱號失敗？** 可能有多位玩家同名，請改用 UUID。

### Plan 選用整合

預設自動接入 Plan，提供 `exists`（玩家資料是否存在）、`title`（稱號）、`count`（全服正式資料筆數）；可用 `plan.fields` 選擇欄位或全部關閉。只讀目前啟用的 ProfileRepository，不讀 staging、搬遷名單、legacy ID、別名或玩家偏好，不作身分判定。Plan Provider 讀 volatile immutable index，不等待儲存、不讀世界或玩家物件。資料庫不就緒時回傳 NotReady，避免虛構空資料。

使用官方 Plan API（provided）；未安裝/停用/能力不足時略過，Plan 啟用停用及插件卸載會註冊/清除整合。Plan 模式與欄位可用下列管理指令即時套用。語系欄位在 `plan`，走 tokaI18n。Plan 網頁閱覽權限及已保存資料由 Plan 管理；停用欄位或更改標籤不會自動刪除 Plan 的歷史資料。


### Plan 模式與管理指令

預設 `plan.mode: auto`、`plan.fields: all`。`auto` 偵測到可用 Plan 就啟用；`enable` 未就緒時提示；`disable` 一定不發布。all 僅包含本插件核准的摘要欄位，沒有憑證、Discord ID 或私人座標。

管理員可用 `/profile plan status`、`/profile plan mode <auto|enable|disable>`、`/profile plan fields <all|none|欄位...>`；變更保存設定且即時套用，保留其他設定及註解。舊 `plan.enabled`／逐項布林仍相容，明確設定的新 mode／fields 優先。

API（主執行緒）：`planStatusJson()`、`configurePlan(String mode, String[] fields)`；null 表示該項沿用。
