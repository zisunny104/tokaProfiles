# tokaProfiles

整體拆分現況只維護於 [拆分主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)；本 README 維護本專案的設定、API 與使用限制。

Nukkit 玩家身分、偏好與稱號服務。版本 **0.2.1**，Java 21，預設 SQLite，也支援 JSON／YAML。PM1E 4511 是目前的測試基準，不是唯一適用核心；其他 Nukkit 分支仍須驗證，不宣稱全部相容。

## 功能與使用限制

| 功能 | 狀態 |
| --- | --- |
| UUID、名稱／歷史名稱、legacy ID | 已實作；不生成替代 UUID |
| 名稱歧義 | 同名不同 UUID 保留不同身分，名稱查詢不任選 |
| 不可變 Profile、背景有界寫入 | 保存成功才發布新快照 |
| 稱號／zero 轉接 | 已抽出；zero 1.7.9 支援 legacy／profiles／disabled，預設 legacy |
| 付費稱號 | Profiles 模式扣款前攔截；跨資料庫交易尚未完成 |
| SQLite／JSON／YAML | 已實作；tokaStorage 私有打包，不需另裝插件 |
| 背景分批搬遷 | checkpoint、中斷續跑、來源備份、完整讀回比對 |
| UUID／名稱衝突 | 私人分組報告與差異欄位；不自動合併、刪除或猜測 |
| Plan 身分對照 | 匯出的 UUID／名稱只作交叉核對；不寫入 Plan |
| 下次重啟切換 | 需停服更新來源與明確確認；驗證失敗保留原有效來源 |
| LuckPerms 指令提案 | 只產生身分群組與基本 Plan 自身資料權限；qx 不自動映射 admin／trusted／OP |
| 城市通 `/profiles` | 個人資料入口；業務選單尚未搬遷 |
| Plan 自訂展示／zero 全部資料接管 | 尚未完成 |

MySQL 業務資料仍須保留；DbLib JAR 已由新版 zero 內嵌接管，移除 JAR 後仍須保留其設定目錄。餘額、有效權限、領地、住家、Discord 綁定與 IP 仍由既有服務負責。完整來源與衝突報告含私人資料，不提交 Git。

## 身分與衝突

UUID 是主鍵，名稱只是索引。`findByName` 遇歧義回傳 empty；`resolveName` 回傳候選 UUID。相同 UUID 多筆來源、同名不同 UUID、重複 legacy ID、缺少或無效 UUID，均列入待確認。Plan 名稱／UUID 矛盾也會阻擋切換；依已確認政策，Plan 的唯一 UUID／目前名稱對照優先；未唯一對照的保留待處理。這不代表 Plan 可替代業務資料合併或授權判斷。

`identity-audit.json` 列出來源 row index、差異欄位與 Plan 對照。封存與有效資料的選擇規則以主文件及 PlanResolution 的實際結果為準；未決業務差異不自動合併。餘額不相加、權限不取最高、Discord 綁定不覆蓋。已有 PlanResolution 離線對照與封存輸出工具；正式 SQL 刪除與定期清理尚未完成。

無衝突資料可先寫入待啟用資料集，但整體有衝突時狀態是 `blocked-conflicts`，不會部分切換有效稱號來源。人工核對後建立新的 stage，原始快照保留。

## 背景搬遷

來源為唯讀、一致的 playerData JSON 快照：rows 陣列，或 `{schema:1, table:"playerData", rows:[...]}`。上限 64 MiB，盤點仍在記憶體執行；不是任意大小的串流匯入器。

```text
java -cp tokaProfiles-0.2.1.jar dev.toka.profiles.migration.StagedProfileMigration stage SOURCE.json sqlite NEW_STAGE 50 100 PLAN_EVIDENCE.json
java -cp tokaProfiles-0.2.1.jar dev.toka.profiles.migration.StagedProfileMigration verify NEW_STAGE CURRENT_SOURCE.json sqlite
```

Plan 證據為 `[{"uuid":"UUID","name":"名稱"}]`，最後一個參數可省略。CLI 等待背景 worker，不啟用正式資料。相同 stage 可以續跑；來源、後端或證據變更就換新 stage。

插件內背景工作：

```yaml
storage:
  backend: sqlite
  active-directory: storage
  queue-capacity: 128
staged:
  enabled: true
  source: legacy-playerData.json
  directory: staged-migration
  plan-evidence: plan-evidence.json
  batch-size: 50
  delay-ms: 100
  activate-next-restart: false
  source-refreshed-after-stop: false
```

路徑限制在插件資料目錄內，不接受符號連結。搬遷寫入獨立資料集；停用時取消 worker 並保留進度。

**線上 SQL 的後續變更尚未自動追蹤。** 切換前正常停服、重新匯出來源。有變更就用新 stage 重跑；不能套用過時快照。來源一致、衝突已解決、讀回通過後，設定 `activate-next-restart: true` 與 `source-refreshed-after-stop: true`。下次啟動驗證通過才更新 `storage.active-directory`，保留舊 storage；啟用後不重跑該 stage。

原 migration.enabled／confirm／source-sha256 離線匯入仍相容，新流程建議 staged。後端切換必須備份與驗證，不能直接改 backend。

## zero 與權限交接

```yaml
profiles:
  mode: shadow
titles:
  mode: legacy # 有效 Profiles 稱號資料完整後才改 profiles
permissions:
  mode: legacy # 功能權限與用詞確認、LuckPerms 驗證後才切換
city-pass:
  mode: legacy
```

Profiles 稱號模式依 UUID 讀寫，zero 不再把稱號寫回 SQL；其他欄位維持原服務。依賴失效不讀過時 SQL 稱號，也不讓整個 zero 停用。資料集啟用與 zero 稱號來源切換是兩步，目前不自動改其他插件的設定。

稱號是顯示資料，不授權。LuckPerms 管理能力節點；領地主人／成員屬區域關係，不等於全服群組。舊 qx 顯示與相容規則不能直接變成新管理權限。現有 `plan-permissions` 只產生未審核的指令提案，不會執行；管理員須確認分組與能力。

## API、編譯與驗證

Main.profiles() 取得 repository。Profile 提供 uuid/name/aliases/preferences/legacyId/title。舊 schema 1 缺 title 時預設空字串。`put` 與 `updateTitle(UUID,String)` 回傳 CompletableFuture；updateTitle 依 writer 最新快照修改，不覆蓋其他欄位。涉及遊戲物件請排回主執行緒，不在玩家事件等待 future。

先安裝同層 tokaStorage，再執行 build.ps1。產物 `output/tokaProfiles-0.2.1.jar`，output 不進版控。Nukkit 核心是 provided dependency，目前編譯基準 PM1E 4511。

11 個單元／搬遷測試通過，包含三後端、衝突、Plan 矛盾、稱號、中斷續跑。核心測試驗證三後端稱號轉接、舊 SQL 稱號不變、依賴失效隔離與重啟啟用。Plan 3638／LuckPerms 5.5.87 共同啟動通過，Plan 註冊 LuckPerms extension。

VPS 玩家資料鏡像已實測：4324 筆來源、3217 筆通過搬遷與讀回比對、1107 筆待確認。比對使用 838 筆 Plan 身分證據；測試 Plan 使用獨立 SQLite，沒有搬入完整歷史分析資料庫。原始快照與正式資料保持不變。

正式資料切換、真人登入、Plan 自訂 Profiles extension、跨服務付費交易與其他 Nukkit 核心分支驗證尚未完成。勿將測試 fixture 的 DbLib 或設定部署正式站。

整體規劃見[拆分主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。歷史盤點以本 README 最新狀態為準。


## 多語系（0.2.0）

所有玩家看得到的文字（稱號指令回應、城市通表單、後台 log、help）集中在 `src/main/resources/lang/zh-TW.yml`，由內嵌的 tokaI18n 讀取；`config.yml` 的 `language` 決定語言（目前只有 `zh-TW`）。伺服器擁有者可把同名檔放到 `plugins/tokaProfiles/lang/` 覆蓋個別文字。新增 `/profiles help`，管理員另可看到稱號與遷移工具的完整用法。啟用資料集或完成離線匯入時改用單行編輯改寫 `config.yml`，不再整檔重存，因此設定檔註解會保留。。共用的多語系原則見[拆分主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。
