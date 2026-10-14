# 插件整體架構與搬遷計畫

更新：2026-10-04。這份文件是整體決策入口，包含功能歸屬、替代方案、效能、安全性與正式搬遷。架構仍是提案；候選插件尚未在 PM1E／Bedrock 26.52 實測，正式資料尚未搬遷。

## 核心決策

- 先確認完整架構，在資料副本反覆驗證，再安排一次正式停服、轉換與驗收。開發測試不是正式搬遷。
- `tokaRegions` 整併 Area、Region、Land 的共用幾何及保護層，保留公共設施、行政區域與玩家領地的不同政策。
- 保留原指令、選單、名稱與管理習慣，透過相容入口呼叫新服務。資料 ID 不因架構整理任意重建。
- 所有新 `toka*` 自製插件的正式資料統一支援 SQLite、JSON、YAML，預設 SQLite；設定維持 YAML。正式 MySQL 在搬遷驗收完成前保留，不提前切換。第三方插件依其支援方式，不強行修改。
- 不一個功能拆一個插件。其他功能先保留 zero／one 內部模組，只有成熟替代品或明確維護收益才另拆 JAR。
- 每份資料與每類操作只有一個權責擁有者，避免兩套保護 listener、世界管理器或餘額服務同時寫入。
- 切換時先關閉 zero 的舊模組、解除 listener 與排程並確認不再寫入，再啟用新服務。切換期間保持維護狀態；新服務失敗不能先開服。啟動檢查若發現新舊同時啟用，拒絕進入正常服務，不自行猜測由誰接管。

## 功能與責任

| 責任 | 建議歸屬 | 邊界 |
| --- | --- | --- |
| 區域、公共設施、領地、村落政策 | tokaRegions | 共用範圍查詢與保護決策；買賣／稅務走經濟介面；不擁有玩家帳戶 |
| 掛載、卸載、更名、維護協調與方塊紀錄 | tokaWorlds | 方塊紀錄為可獨立停用的模組與獨立資料集；呼叫其他資料擁有者的引用介面，不直接改別人的 SQL |
| 傳送與住家 | tokaPortal | 先取得區域許可；不接管區域與世界生命週期 |
| 玩家身分、設定、選單、特殊活動及安全 | zero 的內部模組 | 先保留；通用權限、餘額與傳輸可轉接成熟方案 |
| Discord 特殊整合 | one 或小型 adapter | 通用傳輸評估 DiscordChat；只有一個事件傳送者 |
| 記分板 API、統計 | PM1E 內建 API、既有 Plan | 記分板內容由現有模組管理；統計不等於可還原的操作稽核 |

城市通目前是玩家選單，沒有完整城市模型，不為名稱另建城市插件。Protect 的封禁與特殊工具限制屬安全，不與區域建築保護混為一談。

## tokaRegions 共用模型

共同模型包含穩定識別、名稱、世界識別、範圍、類型、規則與選用傳送點。玩家領地另有所有者、成員與交易政策；行政區域可提供村落與稅務政策；公共設施使用管理政策。不要求所有類型都有 owner 或售價。

保護決策統一回傳允許、拒絕或資料未就緒及原因。找不到領地與服務不可用必須分開。重疊規則採明確且穩定的合併順序；跨世界、邊界包含方式與全高度區域必須寫入契約。改掉舊順序前，要列出新舊行為差異供確認。

依賴由入口指向服務：世界管理呼叫引用介面，傳送呼叫區域許可，聊天訂閱區域事件。區域保護不反向依賴聊天、Discord 或 UI。不要為了共用少量程式建立大型跨插件基底依賴；公開契約應小而穩定。

## 新 toka 插件的儲存規範與實作順序

共用 `tokaStorage` 是打包進插件的 library，不是需要另外安裝的插件。所有新 toka 插件都提供 `storage.backend: sqlite|json|yaml`，預設 sqlite；相同版本化資料模型不可因選擇不同後端遺失欄位或改變金額與識別語意。各插件擁有自己的資料目錄與 store，不直接讀寫另一個插件的 store。

SQLite 以交易保存資料；JSON／YAML 提供單一文件的安全替換與校驗。跨文件與跨插件交易不能當成原子交易，需明確工作紀錄及補償。YAML 只接受安全資料型別；所有後端都拒絕未知較新 schema，避免舊插件覆寫新資料。切換是離線搬遷流程，不是改設定後偷偷匯入或清空。

實作依序為：共用 library 與三後端驗證 → tokaWorlds 資料整合及既有檔案搬遷 → tokaRegions 領域模型／舊 SQL 匯入 → 其他新 toka 插件採同一規範。tokaPortal 正由另一個工作修改，先完成介面與驗證再整合，避免覆寫其變更。正式切換仍等待整體架構與副本驗收。

library 使用不可變快照或防禦性複製，避免呼叫者修改未保存資料。一般事件路徑只查快照，背景有界佇列負責持久化；需要先記錄再執行的維護工作必須等待保存成功後才動世界。停用插件時拒絕新寫入並確認已排入工作完成或明確回報失敗。

第一版 library 是同步文件儲存 API，先用在低頻世界維護狀態；不代表已提供有界背景佇列、區域索引或高頻交易 repository。SQLite 單筆查詢只取需要的文件；JSON／YAML 需讀寫完整文件，第一版限制 16 MiB，達限時拒絕寫入而不是截斷。高頻區域／交易資料在接入前需另外做專用查詢與寫入排程，不能把所有資料塞成一份大快照。

## LuckPerms 與 Plan 的整合決策（2026-10-14）

依使用者決策，先拆共同玩家資料與識別 API，再進行 LuckPerms 搬遷。Plan 的 [支援清單](https://github.com/plan-player-analytics/Plan/wiki/Supported-Plugins)明列 LuckPerms 的 Nukkit 整合；這是專案支援證據，尚不是本服 PM1E 與已安裝版本的實測結果。Plan 不是權限或交易資料的主儲存，LuckPerms 也不接管領地所有權、村落、稱號與餘額。

### 先建立共同玩家資料層

暫稱 tokaProfiles，尚未建立專案或查核名稱。它擁有穩定玩家識別、已驗證的核心 UUID、目前名稱、歷史名稱與共用偏好；其他功能只引用玩家識別。既有 qx／title／job／village 等依各欄位用途建立遷移對照，不把所有玩家相關資料繼續塞進同一份紀錄。

| 資料或能力 | 唯一擁有者 | 其他插件取得方式 |
| --- | --- | --- |
| 玩家識別與名稱對照、共用偏好 | 共同玩家資料插件 | 版本化查詢 API／不可變快照與更新事件 |
| 有效權限與群組 | LuckPerms | 標準權限檢查／公開 API；共同玩家層不另算一套有效權限 |
| 領地與村落政策 | tokaRegions | 引用玩家 ID；顯示摘要由公開 API 提供 |
| 住家 | tokaPortal | 引用玩家 ID，不直接讀共同玩家 SQL |
| 餘額與交易 | 選定經濟服務及業務模組 | 經濟 API，不複製第二份有效餘額 |
| Discord 綁定、Bot 與訊息橋接 | 獨立 Discord 插件／one 的後續整合 | 綁定映射引用玩家 ID，經 API 查詢，不以聊天暱稱判定身分 |
| Plan 展示摘要 | 各資料擁有者的 Plan adapter | 單向提供彙總；Plan 不當身分或授權來源 |

「單一玩家資料」指身分與共用資料有唯一來源，不表示所有插件共用一張大表或互寫資料庫。共同玩家層不依賴 Plan、LuckPerms、Discord、表單或世界管理；外部整合失效仍能查詢玩家身分。其他插件缺身分 API 時停用涉及身分的操作並顯示未就緒，不因查不到玩家而建立另一個帳戶。

Discord 插件獨立儲存綁定映射。綁定需雙方確認的一次性短期代碼，限制嘗試與有效期限；解除與重新綁定重新驗證身分，記錄結果但不公開代碼／Token。在唯一性限制與明確交易下更新映射，不單憑玩家名稱或 Discord 暱稱合併帳戶。Discord 綁定不自動授予管理權；Discord role 到遊戲權限的同步需額外明確政策、驗證與降權流程。

新共同玩家與 Discord 插件遵循 SQLite／JSON／YAML、預設 SQLite 的規範。既有 UUID 缺失、重複、名稱大小寫與 Xbox authentication 模式需先盤點；不自行產生另一套離線 UUID。只在同一份資料快照中遷移後才切換身分來源。

執行順序改為：完整玩家資料欄位與跨插件呼叫盤點 → 身分／資料 API 與搬遷副本 → zero 改委派並關閉舊資料寫入 → LuckPerms 權限搬遷及 Plan 展示 → tokaRegions／Portal 引用接入 → Discord 綁定拆分。各步可獨立測試，正式資料仍依整體一次搬遷流程進行。

### 三層權限，不互相混用

| 層級 | 節點／資料 | 整合方式 |
| --- | --- | --- |
| 遊戲功能授權 | tokaWorlds、tokaPortal、tokaRegions 的獨立節點 | LuckPerms 管理；資料所有權仍由功能模組檢查 |
| Plan 遊戲內指令 | plan.command、plan.player.self／other、plan.ingame.self／other、plan.register.self／other 等 | 透過 LuckPerms 明確授權，不用 plan.* 通配全部 |
| Plan 網頁存取 | plan.webgroup.<名稱> 對應 Plan 網頁群組；網頁內 access.*、page.*、manage.* | 由 Plan web group 控制；網頁節點不直接當遊戲權限 |

來源：[指令與權限](https://github.com/plan-player-analytics/Plan/wiki/Commands-%26-Permissions)、[網頁權限](https://github.com/plan-player-analytics/Plan/wiki/Web-permissions)。文件表格標示 Spigot 預設值，不能當作 Nukkit 的實際預設。Wiki 的指令節點也有不同版本及 export 命名差異，部署前須對照本服 JAR。

網頁群組在註冊時由 plan.webgroup.<名稱> 決定，也能由 Plan setgroup 修改；不能假設 LuckPerms 改群組後既有網頁帳號會立即跟著改。需驗證重新註冊／群組更新、權限撤銷及既有 session，尤其降權；不能只測首次登入。

| 身分用途 | Plan 遊戲內權限提案 | 網頁權限界線 |
| --- | --- | --- |
| 一般玩家 | command、player.self、ingame.self、register.self、unregister.self | 僅自己頁面；其他玩家與查詢頁拒絕 |
| 統計檢視人員 | 額外 server、player.other、ingame.other、search | 選定統計與玩家頁；不給群組管理、資料刪除或完整 raw data |
| 系統維護人員 | 按需 info、reload；backup 等分開授權 | 管理群組與帳號需另外授權，與讀取所有統計分開 |

以上是待確認的角色設計，不是已執行的 lp 指令。Plan 資料 restore／clear／remove／move、register.other／setgroup.other 不能因一般管理身分直接全開。沒有 Plan 時不影響遊戲保護，沒有 LuckPerms 時不得把未知權限判定為允許。

### toka 資料如何接入 Plan

使用 [DataExtension API](https://github.com/plan-player-analytics/Plan/wiki/DataExtension-API---Getting-started)，先 capability 檢查，再註冊獨立 hook class；Plan 缺少、停用或重載時只影響展示，主功能持續。不直接改寫 Plan SQL，亦不把 Plan API 打包成另一份。

使用者確認第一階段可採單向摘要提供。每個重寫完成的功能在新擁有者中實作並測試自己的 Plan adapter，舊入口僅轉接、標記棄用，不另外發送一份重複統計。單向表示 toka → Plan，並非聊天頻道廣播；第一階段沒有 Plan → toka 的資料／操作回寫。尚未完成的 adapter 不宣稱已整合。

| 來源 | 擬接入 Plan 的資料 | 不直接公開的資料 |
| --- | --- | --- |
| LuckPerms | 已有整合的群組／權限相關統計，實際欄位先確認 | 完整權限設定及管理憑證 |
| tokaRegions | 領地數、總面積、成員數、村落標籤 | 精確私人位置、完整成員名單需另評估 |
| tokaPortal | 住家數、成功／失敗傳送統計 | 住家座標與私人名稱 |
| tokaWorlds | 玩家方塊操作總數、世界維護摘要 | 容器完整 NBT、還原 payload、管理備份及秘密引用 |
| 玩家／經濟模組 | 公開稱號、明確允許的活動與餘額摘要 | IP、Discord ID、完整金流、封禁調查細節 |

這些是整合範圍提案，尚未寫好或接入正式 Plan。第一階段預設只匯入可公開摘要；不能假設 API 可對每一個自訂欄位做細粒度保密。敏感管理查詢留在有專屬授權的遊戲／後台流程。

Plan 回呼可能不在遊戲主執行緒，hook 只讀不可變統計快照或有界快取，不能直接遍歷活躍玩家／世界物件。統計採背景彙總，不每次開 Plan 頁面全表掃描或同步查 MySQL。UI 從 Plan 取得統計必須使用支援的 Query／公開 API 並另驗證，不爬網頁也不共用內部表。

### 舊權限資料與搬遷驗收

zero PlayerDataTable 有 name、UUID、qx、title、village 等欄位；QuanXian 與 PlayerInfo 中 qx==0 是最高權限，qx<4 是高權限。顯示將 1／2／3 都稱特別身分，但不能據此將三者無條件合成同一群組。qx=4 是普通，qx=8 有特殊顯示；未知值與負數先報告，不自動授權。原方法 qx<4 也包含負值，不可把髒資料搬成高權限。

先列出每個 qx 實際控制的功能，將顯示稱號與能力拆開；按 UUID 建立玩家對照，處理空 UUID、大小寫與重複帳戶，不假造離線 UUID。保留原階級原值及其他玩家資料，輸出只讀差異與 lp 匯入計畫，再在測試服驗證。

驗收包含一般玩家不可越權、管理能力逐項對照、Plan 自己／他人頁面隔離、網頁群組及 session 降權、無 Plan／無 LuckPerms、插件重載、資料庫失效及效能。副本通過後才安排正式切換，避免舊 qx 授權與 LuckPerms 同時成為有效寫入與決策來源。

## 效能設計

目前 VPS 約 2 GiB RAM，同時有網站、MySQL 與伺服器。架構以減少同步等待、重複查詢與無界快取為目標，不以插件數量當效能指標。

| 路徑 | 設計與驗證 |
| --- | --- |
| 方塊、互動、移動事件 | 以世界與 chunk 索引縮小候選集合，再做精確範圍判斷；禁止每個事件掃全部領地或查 SQL |
| 區域索引 | 大範圍區域避免展開成無限 chunk 清單；按世界保留大區域索引；先量測資料分布再決定結構 |
| 玩家移動 | 只在世界／方塊位置變化時重查必要資訊；避免每 tick 建立表單或重算字串；跨邊界仍做精確判斷 |
| 資料載入 | 背景建立完整快照並驗證，成功後在主執行緒替換；失敗不先清掉可用資料 |
| SQL 與 HTTP | 使用有上限的工作佇列、連線池與 timeout；網路不在主執行緒等待；遊戲物件操作回主執行緒 |
| 快取與通知 | 設容量、失效條件及取消機制；合併重複顯示更新；停用插件時取消工作並釋放資源 |
| 批次搬遷與備份 | 串流分批處理，限制記憶體與 I/O；記錄進度，避免同時執行多個重度工作 |

效能驗收先記錄舊版與新版在同一測試資料的查詢延遲、主執行緒 tick、heap、GC、SQL 次數與佇列深度。以正常負載及尖峰案例比較；具體門檻在基線量測後訂定，不先承諾未量測的效能數字。

## 安全與資料完整性

- 保護資料未就緒時採保守拒絕，不能把讀取失敗當作無人擁有。可信快照可繼續提供保護，但資料寫入暫停。
- 所有指令、表單、排程與 API 入口都重新檢查權限；表單顯示不等於授權。寫入前重查資料版本與玩家狀態。
- Listener 不解除其他插件已取消的事件。列出破壞、放置、互動、容器、展示框、盔甲座、活塞、爆炸、火焰、液體及傷害的政策；未驗證項目不能宣稱受保護。
- SQL 使用參數化查詢；跨領地、餘額與稅務的操作需明確交易或可恢復工作流程。失敗重試不能重複扣款／發獎。
- 更名與備份路徑限定在世界根目錄，驗證解析後路徑、名稱及 symlink；拒絕跨目錄操作與目的地覆蓋。
- 世界更名涉及 SQL 與檔案系統，不能宣稱單一 SQL transaction 保證全部原子性；用持久化工作紀錄、明確步驟與可重入還原處理中斷。
- 管理操作記錄操作者、目標、時間、結果與工作 ID。資料庫密碼、Discord Token、玩家私人資料不寫入公開文件或 Git。
- 候選 JAR 要確認來源、固定版本與雜湊；先測試副本，再安裝正式服。插件需有回復到舊 JAR 與舊資料的方式。
- 備份驗收必須實際還原；開啟中的 LevelDB 直接複製不當成一致性備份。優先停服保存並備份；定時、壓縮與還原尚未實作。

## 現成方案的選用門檻

| 功能 | 候選 | 暫定決策 |
| --- | --- | --- |
| 權限 | [LuckPerms Nukkit](https://github.com/LuckPerms/LuckPerms/tree/master/nukkit) | 優先實測；原階級與稱號需對照，不直接移除 |
| 通用餘額 | [EconomyAPI](https://github.com/Nukkit-coders/EconomyAPI) | 優先實測；購地、稅與商店政策保留；驗證精度、玩家識別與交易失敗 |
| Discord | [DiscordChat](https://github.com/PetteriM1/DiscordChat) | 優先實測通用傳輸；one 特殊功能由 adapter 保留 |
| 世界管理 | [WorldManager](https://github.com/Buddelbubi/WorldManager) | 比較能力，確認更名連動與玩家維護後才決定是否替換 tokaWorlds |
| 區域保護 | [SRegionProtector](https://github.com/SergeyDertan/SRegionProtector) | 最接近的舊 Nukkit 候選；尚無已驗證、近期維護且完整承接現有政策的 PM1E 方案 |
| PNX 區域保護 | [SRegionProtector PNX port](https://github.com/PowerNukkitX-Bundle/SRegionProtector) | 近期維護但目標為 PNX，不能直接當 PM1E 相容 |
| 記分板／統計 | PM1E 內建 API／[Plan](https://github.com/plan-player-analytics/Plan) | 先沿用現有方案 |
| 背包編輯、可回復稽核、一致性備份 | 尚無符合完整要求的已核實替代 | 保留現有資料；先界定功能，不急著新建多個插件 |

2026-10-04 的來源查核只有維護與平台證據，尚無候選的 PM1E 實機驗證。測試涵蓋核心載入、26.52 client、中文／資料、缺依賴降級、效能及還原。只有替代成本與長期維護成本較低才採用。

經濟以現成帳戶／餘額服務為優先，不再自行重建通用經濟插件。業務操作經由 adapter，不直接讀寫 EconomyAPI 的內部資料。轉換前確認原幣別、金額精度、負值與玩家識別；若第三方 API 缺少所需原子交易保證，需補可恢復交易流程，不能假設分開的扣款與領地寫入必然一致。驗收必須確認全體餘額與總額、扣款失敗、重試、重登及還原；只有一套餘額服務可寫入。

## 一次正式搬遷

1. 確認本文件的功能邊界、重疊規則、相容操作與最終資料模型；列出所有改變使用習慣的項目。
2. 在副本完成候選相容測試與搬遷工具。工具產生舊 ID 到新 ID、欄位轉換與差異報告，對未知規則停止處理，不默默丟資料。
3. 比對筆數、ID、世界、範圍、owner、成員、傳送點、政策、餘額及村落關係；再做完整行為驗收與效能比較。
4. 正式停服，保存地圖並備份 SQL、JAR、設定與版本清單，先確認備份可讀。僅部署已驗證版本。
5. 執行一次正式轉換，全部驗收通過後才開服；任何必要驗收失敗就依工作紀錄還原。禁止新舊服務雙寫。
6. 保留原資料與回復工具至觀察期結束。若開服後已有新交易與操作，回復前先處理新增資料，不能直接套舊備份造成遺失。

## 現況與待決策

已完成：zero／one／tokaWorlds 有編譯產物及部分隔離測試；區域功能與候選已盤點。尚未完成：tokaRegions 實作、正式資料搬遷、候選插件實測、全套保護驗收、玩家維護 UI 與新版 tokaPortal 整合。

核心樹葉修正、連接封包與 Block 斷線是另一條驗證工作，不能把核心測試通過當成插件架構或正式地圖驗收通過。

### 儲存第一階段驗收（2026-10-04）

`tokaStorage 0.1.0` 與 `tokaWorlds 1.0.1` 已建立並編譯，產物在各專案根目錄 output。library 打包進 Worlds，不需另裝插件。

| 驗證 | 實際結果 |
| --- | --- |
| library 讀寫、安全、精度與檔案替換 | 13 個測試通過，無跳過 |
| Worlds 舊資料匯入 | SQLite／JSON／YAML 各通過；核對待辦、返回位置、工作紀錄與掛載資料，來源雜湊不變 |
| Worlds 格式轉換 | 九種來源／目標組合通過；既有目的地拒絕覆寫 |
| PM1E 4511 世界操作 | 三後端均通過連動／不連動更名、掛載／卸載、碰撞拒絕、引用守門與強制失敗還原 |
| 停服後保存結果 | 三後端各讀回兩個 complete 工作及一個 rolled-back 工作，排除僅記憶體成功 |
| 未搬遷／只改後端設定 | 實際核心啟動拒絕管理，原始資料保留且不建立空資料庫 |

測試使用 Windows、本機測試世界、SQLite 測試 SQL 與 tokaPortal 2.0.0 fixture，沒有正式 MySQL、VPS 或真實玩家。Linux、實際 UI／玩家返回、最新 Portal adapter、程序強制中斷與停電仍待驗證。tokaRegions 尚未實作，正式資料尚未搬遷。

重現入口在 plugin-modernization/tests：`world_storage_cli.py`、`world_storage_smoke.py <sqlite|json|yaml>`、`world_storage_guard.py`。測試資料保留在 build/storage-*；核心啟動需要公開啟動資訊的網路連線。

以下保留為同一文件的查核附錄，提供現有程式證據與候選來源；主架構決策以上方章節為準。


## 區域功能盤點與拆分計畫

盤點日期：2026-10-04。範圍為本機 zero 1.7.7、one 1.3.10 與 tokaWorlds 1.0.0 的原始碼；沒有修改 Java、編譯、部署或操作正式資料庫。未找到這些專案的 AGENTS.md。本文件是靜態分析，正式資料筆數與遊戲中的完整保護效果仍需另行驗證。

### 目前有哪些功能

| 功能 | 實際用途 | 資料與依賴 | 目前狀態／建議歸屬 |
| --- | --- | --- | --- |
| Area | 道路／鐵路等公共設施的長方體保護；選區、刪除、open／protect／lock | SQL `Area`，世界欄位 `stringLevel`；玩家權限 | 仍在 zero；與 Region、Land 一起放入區域插件 |
| Region | 命名區域或整張世界；區域傳送點、名稱／範圍與 open／protect／lock | SQL `Region`，Java 欄位 `level` 對應 SQL `stringLevel`；玩家分數與管理權限 | 仍在 zero；作為共同區域模型與規則 |
| Land | 玩家領地；購買、轉讓、更名、刪除、成員、傳送點、PVP 類型、選區與管理移動 | SQL `Land`，`stringLevel`、owner、creator、逗號分隔 groupString；玩家、經濟、Area、Region、封禁顯示 | 仍在 zero；與 Area／Region 同一插件，購買透過經濟介面 |
| 村落關聯 | 玩家所屬村落與 Region 名稱比對，影響領地售價與稅務 | SQL `playerData.village`；Region、玩家資料與經濟 | 尚不是獨立城鎮系統；保留區域插件中的村落政策與選用玩家資料轉接器 |
| 城市通 CityPass | 固定物品開啟玩家功能選單；連到領地、傳送、經濟、玩家資料、伺服器管理等 | 共用玩家功能與表單；不是 City 資料表或城市範圍模型 | 留在玩家入口／zero；不因名稱「城市」就搬入區域插件 |
| Protect | 封禁／特殊狀態的行動與互動限制、Toolbox 偵測、清理及管理流程 | 玩家、Convict、Area／Region／Land，另涉及 CityPass 物品 | 是伺服器安全功能，與區域建築保護不同；先留 zero，後續與封禁整併 |
| 區域事件及顯示 | 進出領地事件、玩家目前區域／領地、BossBar 與聊天區域資訊 | PlayerInfo、Players、Chat、Land 事件 | 區域插件發布事件，zero／聊天只訂閱，避免反向依賴 |
| 世界管理 | 掛載、卸載、更名及引用連動 | tokaWorlds；zero 的更名橋接與 SQL transaction | 已有獨立 tokaWorlds；區域插件提供公開引用介面，世界管理不擁有領地資料 |
| Discord | 玩家及聊天整合 | one／JDA，選用 zero 整合 | one 已獨立；區域保護不得依賴 Discord |

Area、Region、Land 目前都有破壞、放置、互動與物品展示框保護。Land 另有盔甲座、活塞、部分方塊更新與傷害處理。這是目前事件範圍，不能當成爆炸、火焰、液體、所有容器或生物破壞皆已涵蓋的保證。

### 已完成與仍未完成

| 項目 | 狀態 |
| --- | --- |
| 依賴失效時隔離模組；其他模組繼續 | 已有第一階段實作，仍有共用資料依賴 |
| 保護未就緒時保守阻止操作 | 已有 ProtectionFallback，覆蓋破壞、放置、互動、展示框、活塞、傷害 |
| 保護載入筆數與世界引用檢查 | 已有 WorldLinks.audit／problem；有意卸載另記錄 world-mounts.yml |
| 進出訊息與 chat 分離 | 已實作 presence-messages |
| 區域功能拆成獨立插件 | 尚未實作，本文件規劃 |
| 玩家、經濟、區域公開 API | 目前仍有大量 static 呼叫；需要逐步抽出 |
| JSON／YAML／SQLite 儲存切換 | 本階段不做；依使用者決定維持現有 DbLib／SQL |
| 遊戲內全面保護驗證、正式資料對照 | 尚未完成 |

### 已看到的風險

1. **缺資料與沒有領地不能混為一談。** 模組停用目前可導致全服保守限制；拆分後 API 必須明確回傳 READY／UNAVAILABLE 與理由，查無領地才可回傳空值。管理功能不能繞過未知狀態寫入資料。
2. **區域重疊缺乏明確優先序。** Area／Region／Land 用 HashMap 遍歷並取第一個符合；相同範圍重疊時未見穩定排序。新模型先訂定公共設施、區域規則、領地的決策合併規則，保持既有行為的測試案例後才調整。
3. **多個 listener 可能互相改寫取消狀態。** LandMain.onEntityDamage 存在 `setCancelled(false)`；不能把這視為已確認漏洞，但必須測試它是否解除其他保護插件的限制。新決策層應只增加拒絕，不自行解除別人的取消。
4. **世界名稱與 Region 名稱混用。** Land 的 blacklist 與 v1～v4 全高度政策硬編碼為 Region 名稱；world rename 不等於 Region rename。PlayerData.village 是 Region 關聯，不能全域字串取代。
5. **空值、界線與舊高度假設。** LandPrice／inRegion 等直接取 Region／玩家資料，未就緒可能例外；全高度領地用 59～256 判斷。需保留舊資料語意並驗證現行核心世界高度。
6. **重載不是可靠恢復。** reload 先清除集合再載入，錯誤時可能只剩部分資料。新服務應完整建構下一份快照、檢查成功後再替換；第一階段仍採完整重啟恢復。
7. **保護查詢重複遍歷。** getArea／getRegion／getLand 有多餘內層迴圈；先寫邊界與重疊測試，再換按世界／chunk 索引，不能以效能修改掩蓋規則變更。
8. **世界連動版本限制。** tokaWorlds 的 tokaPortal 轉接器目前只接受 2.0.0；其他版本需另驗證，不應拿資料格式猜測直接連動。

### 建議拆分：按概念整併

暫稱 `tokaRegions`，名稱尚未查衝突也未建立倉庫。它一起承接 Area、Region、Land、村落政策、選區、保護 listener 與進出事件，內部仍分模組。不要拆成 Area.jar、Region.jar、Land.jar 三個互相依賴的插件。

依賴方向：tokaWorlds → 區域引用介面；tokaPortal → 區域查詢／傳送許可介面；zero 玩家與聊天 → 區域事件／查詢介面。區域幾何查詢與已載入保護規則不反向依賴聊天、Discord、表單或傳送插件。

現有特殊政策需要玩家權限、分數、村落與經濟。以選用 adapter 提供：缺少經濟時關閉購買／退費，但既有領地保護仍運作；玩家分數無法取得時，相關 protect 規則採拒絕並顯示原因，不能全部放行。儲存依賴失效時保留最後一份已驗證快照並切為不可寫；若根本沒有可信快照，採保守限制。

zero 暫留玩家資料、城市通入口、聊天／顯示與伺服器安全；one 留 Discord；tokaPortal 留傳送；tokaWorlds 留世界生命週期。未來安全／封禁或經濟是否獨立，等區域拆分穩定後評估，不一次改所有插件。

### 執行順序與驗收

| 步驟 | 工作 | 驗收／回復方式 |
| --- | --- | --- |
| 1 | 補區域決策與現有資料的測試；盤點所有跨模組呼叫 | 邊界、重疊、PVP、展示框、活塞、未掛載、缺 DB／玩家資料案例可重現；不改正式 SQL |
| 2 | 在 zero 抽出只讀 RegionService、ProtectionDecision、事件與世界引用契約 | 原指令／資料維持；one、tokaPortal 不再直接取得內部實體集合 |
| 3 | 建立 tokaRegions，先以只讀比對模式載入相同 SQL | 新舊結果逐筆比對；禁止雙寫與雙重保護 listener；不重建 ID 或改表格式 |
| 4 | 停服備份後切換區域資料的唯一寫入者與 listener | 原表筆數、ID、owner、成員、世界、選區、傳送點一致；zero 保留相容 API 委派 |
| 5 | 缺依賴與遊戲內驗收，再移除 zero 的舊區域實作 | 既有領地在缺經濟／Discord／傳送時仍受保護；缺可信資料時不放行；可還原舊 JAR 與 SQL 備份 |

成熟替代插件已補上第一輪公開資料調查，見下表；尚未實際編譯或載入候選 JAR，不能直接指定已可替換。特殊村落稅務可以留小型整合模組，但不可再讓整服功能硬依賴它。

### 現有插件替代方案（2026-10-04 查核）

日期以各倉庫 GitHub API 的預設分支最新 commit committer date、最新 release published_at 為準，下面只列日期。`pushed_at` 可能是其他分支活動，不能代替實際程式更新時間。近期維護、有完整功能與已驗證適用 PM1E 是三件不同的事：本輪**沒有任何候選完成 PM1E 4511 遊戲內驗證**。

| 候選／原作者來源 | 最近 commit／release | 宣告核心與成熟度證據 | 可替代範圍／判斷 |
| --- | --- | --- | --- |
| [SRegionProtector 原版](https://github.com/SergeyDertan/SRegionProtector) | [commit](https://github.com/SergeyDertan/SRegionProtector/commit/65c9563d471551482e8a738f166ea58e6310d692) 2021-06-13；[30.0](https://github.com/SergeyDertan/SRegionProtector/releases/tag/30.0) 2020-07-29 | Nukkit／NukkitX；有 API、表單、flags 與多年提交紀錄，最近預設分支程式維護已久；未宣告 PM1E 4511 | 接近 Area／Region／Land 的通用保護與成員；適合列為「先實測」候選，不能算近期活躍且已相容 |
| [SRegionProtector PNX port](https://github.com/PowerNukkitX-Bundle/SRegionProtector) | [commit](https://github.com/PowerNukkitX-Bundle/SRegionProtector/commit/b1fa6330ffd8ce14a26265977d32c3af7652452b) 2026-07-29；[v30.2](https://github.com/PowerNukkitX-Bundle/SRegionProtector/releases/tag/v30.2) 2026-07-10 | README 明確要求現行 PNX、Java 21；pom 依賴 org.powernukkitx:server，非 PM1E | 近期有維護，功能最接近，含優先序及 SQL provider；僅列移植／功能參考，不能把 PNX JAR 直接推薦給 PM1E |
| [fromgate/Regions](https://github.com/fromgate/Regions) | [commit](https://github.com/fromgate/Regions/commit/47820e25c5c64ba09e1cec28a0d4a791c1edb55c) 2017-12-18；未找到 GitHub release | Nukkit；有 owner／member／flags 與 world editing，未見現行 PM1E 支援聲明 | 基本區域功能接近，但維護年代過久，本次不選為新的核心依賴 |
| [WorldGuard-Nukkit](https://github.com/qXoap/WorldGuard-Nukkit) | [commit](https://github.com/qXoap/WorldGuard-Nukkit/commit/b48fc99f06abda3a816282a3c4224fd5b4409024) 2024-11-12；[v1.0.0](https://github.com/qXoap/WorldGuard-Nukkit/releases/tag/v1.0.0) 同日 | Nukkit；README 說玩家阻擋系統尚未正常運作，沒有 PM1E 版本矩陣 | 可參考部分 flags；不適合替代現有完整領地保護，也不是 EngineHub 的 WorldGuard |
| [worldRegion](https://github.com/Myenov/worldRegion) | [commit](https://github.com/Myenov/worldRegion/commit/04c435fdc12b9880373aa351c91ba8305914cb07) 2026-05-11；未找到 release | README 為作者第一個 Java 插件，目標 Nukkit-MOT 26.20；有階層與優先序 API | 新近但屬不同核心、成熟度證據不足；不採用作正式保護依賴 |
| [FrostTowny](https://github.com/frostbitemsua-sketch/FrostTowny) | [commit](https://github.com/frostbitemsua-sketch/FrostTowny/commit/6e49050fdcdda34bd9574dfb68d7f95901a47ff3) 2026-03-15；未找到 release | README 宣告 Nukkit 城市／居民／領土玩法，資訊短，未找到 PM1E 4511 支援證據 | 可列城鎮玩法後續評估；目前的 village 只是價格政策，沒有必要為它導入完整城鎮／戰爭系統 |
| [ProtectedWorlds](https://github.com/PetteriM1/ProtectedWorlds) | [commit](https://github.com/PetteriM1/ProtectedWorlds/commit/60428f6396fe1985b7b807a8f01743b9989e74e9) 2024-01-10；未找到 GitHub release | PM1E 維護者的 Nukkit 世界保護插件；pom 1.1.2、Nukkit 1.0-SNAPSHOT；作者相同不等於已驗證新核心 | 可以測試整張世界的簡單保護，不能替代玩家領地、村落稅或特殊狀態 Protect |
| [WorldManager](https://github.com/Buddelbubi/WorldManager) | [commit](https://github.com/Buddelbubi/WorldManager/commit/f05c8b010ec3a830196bd58aef474171f4c61e69) 2025-03-14；[1.2.3](https://github.com/Buddelbubi/WorldManager/releases/tag/1.2.3) 2024-08-24 | Nukkit 世界管理，具掛載／更名／備份／UI 等公開功能；未見指定現行 PM1E 版本 | 可評估取代 tokaWorlds 的一般世界操作，但沒有已確認的 zero SQL／住家精確更名介面；不能先用更名後再補資料 |
| [BlockProtect](https://github.com/HiddenMotives/BlockProtect) | [commit](https://github.com/HiddenMotives/BlockProtect/commit/f9823dbd667d3d79c2006f1353bf3c67d32296a3) 2018-05-26；[1.0.0](https://github.com/HiddenMotives/BlockProtect/releases/tag/1.0.0) 2016-05-08 | Nukkit；以保護方塊／半徑為主，README 將多世界列為待擴充 | 與現有多世界長方體領地差異大且久未更新，不採用 |

本輪結論：通用區域保護確實已有完整歷史實作，近期 PNX port 也可參考；**尚未找到同時具近期維護、明確 PM1E 支援、可直接接現有 SQL 的完整替代品**。這不是宣稱全網不存在，而是本次原作者倉庫／README／release／commit 查核的結果。先實測原 Nukkit SRegionProtector 的相容性，若差異修補與資料轉接成本低，採現成插件加小型政策 adapter；否則依本文件拆出自己的區域插件。

#### 替換與資料驗證要求

- 候選有 MySQL 功能不代表能直接讀 zero 的 `Area`／`Region`／`Land` 表。SRegionProtector 的 provider migration 是它自己的格式轉換，不是 zero importer。正式資料仍維持原 SQL，測試時從唯讀副本產生目標資料，不覆蓋來源。
- 精確保留世界、兩點座標、包含邊界、owner／creator、成員、傳送點與既有 ID 對照；Area open／protect／lock、Region 分數 175 與村落折扣／稅率必須明確轉成規則或 adapter，不能遺漏。
- 候選實測包括無經濟／無玩家資料／無 Discord、世界未掛載、重疊優先序、活塞跨界、爆炸／火／液體、容器／展示框／盔甲座與 PVP。缺資料不能回傳「沒有領地」。只成功開機不足以通過。
- 最後切換時只有一個資料寫入者與一組保護決策，不同插件不可互相 `setCancelled(false)` 解封。保留舊 JAR、SQL 備份及逐筆對照表，確認可回復。
- 若移植 GPL-3.0 SRegionProtector 的程式，需要保留來源與授權，遵守衍生發布要求；不能將它當成沒有授權限制的自有程式。

#### 自製名稱查核

2026-10-04 以 GitHub repository search `tokaRegions in:name`、`tokaTerritories in:name`、`tokaClaims in:name` 查詢，三者皆為 0 筆公開倉庫命中。搜尋索引可能漏掉私人或新倉庫，也沒有涵蓋商標／所有套件平台；這只是暫時未見公開名稱衝突，並非名稱保留。

建議 **tokaRegions**：涵蓋公共保護區、玩家領地與村落政策，不把範圍縮到 Claims，也不暗示獨立戰爭系統。專案名稱可用 `toka-regions`，插件名 `tokaRegions`，Java package `dev.toka.regions`。使用者尚未確認建立專案，本次沒有新增倉庫或程式。

### 原始碼證據

以下為本機相對路徑；zero 與 one 均位於 B:/IdeaProjects，行號可能隨後續修改移動。

- `zero/src/main/java/prj/toka/zero/Main.java:37`：模組與依賴載入；`:73` 起為世界橋接。
- `zero/src/main/java/prj/toka/zero/ser/area/Area.java`、`AreaMain.java`、`AreaTable.java`：範圍、權限、事件與 Area SQL。
- `zero/src/main/java/prj/toka/zero/ser/region/Region.java`、`RegionMain.java`、`RegionTable.java`：world／region 類型、傳送點、分數 175 的 protect 條件。
- `zero/src/main/java/prj/toka/zero/ser/land/LandMain.java:45`：硬編碼 blacklist；`:54` 起價格／村落稅務；`:630` 起保護事件；`:785` 起領地移動。
- `zero/src/main/java/prj/toka/zero/ser/land/Land.java`、`LandTable.java`：owner／creator／groupString、範圍與傳送點；hasPermission／getPermission 尚為固定 false，不當成可用細項權限。
- `zero/src/main/java/prj/toka/zero/player/playerdata/PlayerDataTable.java:29`：village 欄位。
- `zero/src/main/java/prj/toka/zero/player/citypass/CityPass.java:81`：城市通是跨功能選單。
- `zero/src/main/java/prj/toka/zero/runtime/ProtectionFallback.java`、`WorldLinks.java`：保守限制、筆數與世界引用檢查、已知引用更名。
- `zero/src/main/java/prj/toka/zero/ser/server/protect/Protect.java`：安全／Toolbox／狀態限制，並非區域範圍儲存。
- `zero/src/main/java/prj/toka/zero/player/Players.java:202`、`ser/chat/Chat.java:98`：玩家／聊天消費 Region；LandForm 回到 CityPass。
- `tokaWorlds/src/main/java/dev/toka/worlds/Links.java:29`：zero 更名介面與 tokaPortal 2.0.0 版本限制。
- `one/README.md` 與 `zero/README.md`：第一階段紀錄；本文件不把既有測試紀錄當成本次重新執行的結果。


## 現成插件替代評估

查核日期：2026-10-04（台灣）。目標環境：PM1E 4511、Java 21、既有 MySQL／DbLib 資料。PM1E 是 Nukkit 的第三方分支；本文件的「專案發布版本」不代表 Minecraft 官方軟體。

本次只查閱維護者的 GitHub、plugin.yml、Maven 設定及作者發布頁。**尚未下載或安裝候選插件，也未證明它們在目前 PM1E、Bedrock 26.52 與既有資料上可用。** 最近有 commit 是維護活動證據，不等於已修好所有問題；舊但穩定的插件也不因日期早就必然不可用。

### 建議方向

優先測試 LuckPerms、DiscordChat、EconomyAPI；保留已使用的 Plan。記分板先沿用 PM1E 內建 API，不為 API 本身新增插件。多世界管理比較 WorldManager，但本服的 zero／tokaPortal 更名連動與玩家維護還需要自己的 adapter。其他特色功能按概念分組，保留單向、選用整合，不再把全部功能塞進一個插件。

### 候選與支援證據

表中「最近 commit」是當日直接讀取 GitHub API 預設分支最新 commit 的日期，非搜尋引擎的擷取日期。表列日期均未顯示 archived。整個 repository 的日期不代表 Nukkit 子模組同日有修改；尤其 LuckPerms、Plan 必須另測 Nukkit 產物。

| 功能 | 候選／最近 commit | 支援與功能證據 | 判斷與缺口 |
| --- | --- | --- | --- |
| 通用權限 | LuckPerms，2026-09-19，`25f223317a` | 專案保留 [Nukkit module](https://github.com/LuckPerms/LuckPerms/tree/master/nukkit)，提供權限、群組、API及管理工具 | 優先候選；必須選 Nukkit 產物。零散的數字階級、稱號與村落身分不會自動轉移。 |
| 經濟帳戶 | Nukkit-coders/EconomyAPI，2026-05-28，`4563b05d03` | [專案](https://github.com/Nukkit-coders/EconomyAPI)明列 Nukkit 經濟 API；pom 為 Nukkit 1.0-SNAPSHOT，plugin.yml API 1.0.13 | 有近期維護，值得測試；只替代通用餘額 API，不直接取代稅、領地購買、商店及礦物獎勵規則。 |
| Discord 傳輸 | PetteriM1/DiscordChat，2025-09-04，`f0f118d02c` | [作者發布頁](https://cloudburstmc.org/resources/discordchat.137/)有雙向聊天、進出／死亡訊息、訊息佇列、可調設定及 API；[原始碼](https://github.com/PetteriM1/DiscordChat)依賴 Nukkit，API 1.0.0 | 最符合 one 通用傳輸功能的候選；有效 Token、Message Content Intent、中文字與停服流程仍需測試。自訂 Bot 管理功能可能仍要小型 adapter。 |
| Discord 替代舊候選 | MinecraftDiscordSync，2021-04-04，`c296f21f8e` | [README](https://github.com/VironLab/MinecraftDiscordSync)列 Nukkit API 1.0.9+，並明確寫 PowerNukkit 未測試 | 沒有近期維護證據，不列為第一選擇；不能因跨平台或帳號綁定功能就當成 PM1E 相容。 |
| 多世界管理 | Buddelbubi/WorldManager，2025-03-14，`f05c8b010e` | [專案](https://github.com/Buddelbubi/WorldManager)列掛載、卸載、更名、複製、世界設定與 UI；pom 為 Nukkit | 功能廣、值得比較。沒有證據涵蓋本服 SQL／住家引用連動、批次還原及離線玩家返回，不直接取代已做的 tokaWorlds。世界複製不等於完整壓縮／定時／離站備份。 |
| 記分板內容 | ign-gg/SimpleScoreboards，2025-12-23，`3c012cc2d5` | [原始碼與 plugin.yml](https://github.com/ign-gg/SimpleScoreboards/blob/master/src/main/resources/plugin.yml)指向 Nukkit，API 1.0.15；pom 有 PlaceholderAPI | 可供可設定內容評估，不能把 API 插件與成品側邊欄混為一談。現有 zero 已改用 PM1E 內建 API，暫不新增依賴；是否涵蓋本服欄位待查。 |
| 舊 ScoreboardAPI | LucGamesYT/ScoreboardAPI | [README](https://github.com/LucGamesYT/ScoreboardAPI)區分 Nukkit 1.0 與 2.0 分支／版本 | 不建議為目前已具備的 API 再裝一套；Nukkit 2.0 產物不能視為 PM1E 適用。 |
| 聊天群組格式 | Nukkit-coders/MultiChat，2023-06-13 | [README](https://github.com/Nukkit-coders/MultiChat)及 plugin.yml 明確依賴 Multipass，不支援其他權限插件 | 與 LuckPerms 方向不合；也未證明能處理本服進出文字、稱號、區域聊天及封禁邏輯。暫保留本服訊息模組。 |
| 背包查看／編輯 | tim03we/InvSeeNK，2020-05-21，`86c1a034f5`；lt-name/InvSee，2021-08-23，`50817a25f1` | [InvSeeNK](https://github.com/tim03we/InvSeeNK)標明 Nukkit，並要求 FakeInventories；[lt-name/InvSee](https://github.com/lt-name/InvSee)是另一個查看／編輯方案 | 目前找到的候選缺乏近期維護證據；近期物品狀態、離線編輯、防複製及視窗封包必須測試。不要直接恢復舊 JAR。 |
| 分世界背包 | PetteriM1/WorldEssentials，2023-08-09 | [作者發布頁](https://cloudburstmc.org/resources/worldessentials.148/)明列分世界背包、終界箱與模式，亦註記既有背包資料處理限制 | 這與 InvSee 不同，且不是目前需求的必要功能。若以後需要，先做資料匯入與還原實驗，不直接切換玩家資料儲存者。 |
| 玩家統計 | Plan，2026-10-03，`54aad5837c` | [專案](https://github.com/plan-player-analytics/Plan)仍有 [Nukkit module](https://github.com/plan-player-analytics/Plan/tree/master/Plan/nukkit) | 既有 Plan 可保留。玩家統計不等於方塊稽核、物品稽核或可回復破壞的紀錄系統。 |
| 方塊／管理稽核 | 本次沒有核實足以直接替換的近期 Nukkit 候選 | 原有 PlayerLog JAR 的名稱不足以確認其來源及能力；沒有將 Bukkit 的 CoreProtect／PlayerLogger 算成 Nukkit 方案 | 保留現有紀錄，先盤點能記什麼、保存多久、能否還原，與權限存取隔離。不能把紀錄功能宣稱成回復工具。 |
| 壓縮／定時備份與還原 | 本次沒有核實符合完整要求的近期 Nukkit 插件 | [Nukkit AutoSave](https://github.com/jungwuk-ryu/Nukkit-AutoSave-Plugin)是呼叫插件 save／onDisable；不是一致性壓縮備份與還原 | 建議先以 VPS 停服備份流程處理，再整合 tokaWorlds 的保存／維護介面。線上直接複製開啟中的 LevelDB 不當成可還原備份。 |

排除錯誤平台：InvSee++、DiscordSRV、Multiverse、一般 Bukkit／Paper 的 MultiWorld，不因名稱或功能相同就可裝入 PM1E；PowerNukkitX 專用插件也不能據此認定適用。

### 搬遷方式

1. **權限**：列出既有數字階級到 LuckPerms 群組的對照，以及 OP、管理員、玩家權限。先讓資料只讀比對，不立即移除原有階級。稱號與城鎮身分留在玩家領域資料，使用選用 API 提供顯示。
2. **Discord**：先測 DiscordChat 的基本橋接、進出／死亡、訊息佇列與無效 Token。再列出 one 特有 Bot 指令與管理通知，只搬通用傳輸。切換時只能有一套傳送相同事件，避免重複訊息；Token 保留在伺服器設定，不進文件或 Git。
3. **經濟**：維持現有資料庫，先做只讀餘額核對與 adapter。確認玩家識別、大小寫、金額精度、負值、交易失敗及重複扣款的語意後，才考慮停服匯入。領地和商店不直接操作第三方資料表；停用經濟服務時停交易，保護照常。
4. **多世界**：在測試副本比較 WorldManager 的能力與 tokaWorlds。任何世界更名都先驗證 zero／tokaPortal 引用，若成熟工具只缺連動，可縮小 tokaWorlds 為維護協調與 adapter；不讓兩套管理器同時更名、卸載或保存同一世界。
5. **背包與備份**：使用虛構玩家及測試物品，測線上／離線、完整 NBT、網路中斷、重登、完整停服後還原。取得來源與實測前，保留既有正式資料。

以上為計畫，尚未執行資料搬遷或安裝。可採用的標準至少包含：PM1E 實際載入、26.52 用戶端、原有資料不變、依賴失效時合理降級，以及可用的還原步驟。

### 尚無合適替代時的概念分組

名稱僅為提案，不代表已建立新專案；不採一個功能一個插件。

| 分組提案 | 收納功能 | 依賴方向 |
| --- | --- | --- |
| `tokaProfiles` | 既有玩家資料、稱號與身分、玩家設定；通用權限接 LuckPerms | 不依賴 Discord、區域或 UI；提供小型只讀 API |
| `tokaCommunity` | 遊戲聊天、進出／死亡文字、廣播、MOTD、通知格式 | 玩家顯示與區域資訊為選用；DiscordChat 為選用傳輸 |
| `tokaCommerce` | 商店、轉帳、稅、礦物獎勵與交易規則 | 使用經濟 adapter；不反向依賴世界管理 |
| `tokaActivities` | 每日獎勵、投票、玩家活動表單 | 外部 HTTP 失敗不阻止登入；經濟為選用但未確認獎勵不得發放 |
| `tokaAdministration` | 管理操作稽核、方塊紀錄與封禁整合 | 不將 SQL／外部同步失敗擴散到登入文字；回復能力另驗證 |
| `tokaWorlds` | 世界維護、掛載／卸載／更名、連動協調及後續備份入口 | 透過明確 adapter 通知其他資料擁有者；不直接接管他們的資料 |

區域、領地、村落及城鎮需另做共同領域盤點，不在此先拆成多個 JAR。傳送與住家仍交由 tokaPortal，避免 Community 或 Profiles 再包一套傳送。

資料格式維持現有資料庫；本階段沒有改成預設 SQLite。所有候選在隔離測試通過後，才排定逐項替換。

### 可重查的維護來源

GitHub commit API 形式為 `https://api.github.com/repos/<owner>/<repo>/commits?per_page=1`。上表的維護日期來源如下：[LuckPerms](https://api.github.com/repos/LuckPerms/LuckPerms/commits?per_page=1)、[EconomyAPI](https://api.github.com/repos/Nukkit-coders/EconomyAPI/commits?per_page=1)、[DiscordChat](https://api.github.com/repos/PetteriM1/DiscordChat/commits?per_page=1)、[WorldManager](https://api.github.com/repos/Buddelbubi/WorldManager/commits?per_page=1)、[SimpleScoreboards](https://api.github.com/repos/ign-gg/SimpleScoreboards/commits?per_page=1)、[Plan](https://api.github.com/repos/plan-player-analytics/Plan/commits?per_page=1)、[MultiChat](https://api.github.com/repos/Nukkit-coders/MultiChat/commits?per_page=1)、[InvSeeNK](https://api.github.com/repos/tim03we/InvSeeNK/commits?per_page=1)、[InvSee](https://api.github.com/repos/lt-name/InvSee/commits?per_page=1)、[WorldEssentials](https://api.github.com/repos/PetteriM1/WorldEssentials/commits?per_page=1)、[MinecraftDiscordSync](https://api.github.com/repos/VironLab/MinecraftDiscordSync/commits?per_page=1)。

## 2026-10-14 權限盤點與設計提案（尚未套用）

所有 toka 插件以 Nukkit 為平台定位。PM1E 4511 是目前已驗證的核心版本；不把它寫成唯一適用平台，也不宣稱未測試分支已相容。

權限先盤點、確認用詞與設定格式，再實作。以下角色、能力與降級策略均為提案，不是現有插件已提供的功能。稱號與玩家 UUID 屬 tokaProfiles；稱號不授權。

### 現有機制與缺口

| 範圍 | 現有判斷 | 需要保留／轉換的關係 | 目前缺口與後續方向 |
| --- | --- | --- | --- |
| 全服身分 | zero qx 0 為最高，qx < 4 為較高；兼容橋接 admin/trusted | 舊身分顯示、管理操作能力 | 不直接由 qx 生成有效管理權限；負數等未知值需另外拒絕／核對，待實作 |
| Area／道路／鐵路設施 | open／protect／lock；protect 查最高身分 | 設施範圍、操作種類、維護者 | 保留鎖定語意，不能用普通建造權繞過 |
| Region／世界範圍 | open／protect／lock；protect 允許 score >= 175 或最高身分 | 原進度門檻與區域政策 | score 是動態條件，不等同永久 LuckPerms 群組或居民身分 |
| 領地 | owner／creator 與分享名單以玩家名稱保存，另有 OP 例外 | 主人、建立者、被分享者 | 分享名單不等於主人；hasPermission(type) 是未完成 stub，不能宣稱已有細分能力 |
| Cube／設施新版草稿 | Region／Area／Land 混合判斷；部分保護事件空白 | 不同區域型別與空間範圍 | 尚不能取代舊保護；舊轉換含固定建立者，不直接沿用為新所有權 |
| 村落／城市／居民 | playerData.village、選村與標籤、城市通介面 | 居民隸屬與公共設施使用 | 目前不是完整居民角色／繼承系統；不能憑村落字串推定管理權 |
| 傳送與住家 | Portal 同時使用 OP 與 zero 最高身分；建立者名稱 | 住家擁有者、傳送點建立者、他人住家管理 | 直接 OP 例外尚未逐項轉接；所有權與使用指令權分開 |
| 世界管理 | tokaWorlds.use、tokaWorlds.admin；排程／視窗回應再次檢查 | 掛載／卸載／更名執行權 | 管理權不授予區域建造豁免；世界更名 context 更新仍需設計 |
| 經濟／贊助／稱號 | 有 OP、最高身分及直接 qx==0；贊助可能改 qx | 扣款、贊助紀錄、展示身分 | 不由贊助或稱號自動授予管理能力；交易與授權需分開 |
| 告示／方塊紀錄／保護 | OP 與最高身分混用 | 查看紀錄、維護、豁免、還原 | 不共用單一 admin 開關；還原尚未完整實作 |
| Discord 後台 | one 透過 zero 的離線 PlayerData 檢查最高身分 | 已驗證 Discord 綁定、遠端指令能力 | LuckPerms 離線 context 尚未實作；不能因綁定就授予後台權限 |
| Plan | 使用 Plan 自己的 permission nodes／web groups；已註冊 LuckPerms extension | 自己資料、他人資料、網頁管理 | Profiles 自訂 extension 未完成；不把所有 toka 管理員都映射 web admin |

上述範圍均納入 tokaRegions／共用授權介面設計，不只領地。現有 source 的直接 isOp、qx 比較、名稱所有權仍保留於舊流程；目前不能只改 permissions.mode 就宣稱全部權限搬遷完成。

### 授權責任與角色繼承

- Profiles：已確認 UUID、名稱歷史、稱號；不提供有效管理權限。
- LuckPerms：全服／世界的功能能力、context、群組繼承。功能檢查解析後的能力值，不用群組名稱、最高 primary group、prefix 或稱號推定授權。
- Regions：區域、設施、領地、城市、村落與居民的關係、角色與政策；角色限定該 scope。原建立者、擁有者與分享成員為不同關係。
- Worlds：世界生命週期與更名連動；不裁決居民或領地成員。
- Plan：單向展示摘要；不回寫 Profiles、Regions 或權限。

領地主人、分享成員、設施維護者、城市管理者、居民、訪客可以使用不同角色定義。繼承由設定描述，須驗證引用存在與無循環；不把所有角色硬寫成 Java enum，也不為每塊領地建立全服權限組。

提案：以 scopeType + scopeId + playerUUID 保存角色關係，以 action 的能力名稱查角色圖。角色可繼承本 scope 的基礎角色；跨 scope（例如居民可使用城市公共設施）須有明確連動規則，不能因同名角色自動繼承。擁有者有資源管理能力，分享者不因此取得轉讓所有權或再分享權。角色名稱及預設能力待管理員確認。

### 缺少 LuckPerms 的降級提案

| 狀態 | 基本功能 | 複雜功能 |
| --- | --- | --- |
| 未安裝 LuckPerms | OP／一般玩家；已確認擁有者仍能操作自己的資源 | 分享、居民分級、自訂角色與繼承停用，資料保留 |
| LuckPerms 已安裝且就緒 | 依解析後的能力與目標 scope 判斷；不額外 OR isOp | 完成資料與 context 驗證的功能才啟用 |
| LuckPerms 已安裝但儲存或載入失敗 | 暫停相關授權，不把故障誤當未安裝而退回 OP | 停用相關功能，保留角色資料 |
| 已啟用複雜權限後，LuckPerms 被移除 | 依明確確認的降級設定恢復基本模式 | 原關係不刪除，不重新解讀為擁有者 |

「降級」只針對基本模式，不保留暗藏的分享權。未知 UUID／有歧義名稱不能推定擁有者。OP 是否能繞過 lock、跨世界管理、代管他人資源須逐項確認，不直接全部允許。這張表尚未寫入現有插件程式。

### 決策、context 與效能

操作要提供操作者 UUID、目標世界／scope、action 與資料版本。以方塊、容器、住家或待管理資源的位置判定 scope，不只查玩家腳下位置。遠端操作不可沿用玩家所在領地的 context。重疊區域的覆寫順序、lock 與明確拒絕優先序需設定並驗證，不取任意 HashMap 第一筆。

基本能力通過後，再判斷目標 scope 的所有權／角色與政策。若有 bypass，必須是獨立能力並記錄使用；不把一般世界管理權當作所有區域的 bypass。對 LuckPerms 使用解析後結果與正確 QueryOptions，不能自行宣稱 false 永遠優先於所有 true；繼承、精確節點與通配的解析應交給 LuckPerms。

區域 context 的動態值必須由插件註冊與計算，不是只在指令尾端寫 context 就會生效。離線玩家沒有即時位置，須異步載入 User 並明確指定目標 query options；未知情況拒絕。角色或 context 更新要使快取失效；事件主執行緒不等待 SQL／Future。

### 驗證與實作順序

1. 管理員確認術語、功能能力與角色矩陣；備份現有 qx／OP／所有權／居民／分享資料。
2. 在私人鏡像進行名稱到 UUID 對照；有衝突就保留舊關係，不猜測或授權。
3. 權限先 shadow，逐項記錄舊／新決策差異；居民、score 門檻、分享與所有權分開測試。
4. 驗證一般玩家、OP、擁有者、分享者、居民、管理者、跨 scope、繼承、明確拒絕、未設定、context、循環角色、依賴失效、撤權後視窗與排程。
5. 逐模組切換；新舊保護不同時開啟。新流程完成才棄用直接 qx／isOp 判斷。

已完成的實測僅有 LuckPerms 5.5.87 在 Nukkit 測試核心上的解析：群組繼承、目標世界／區域隔離、精確拒絕覆蓋通配與未設定拒絕，以及 Plan 3638 註冊 LuckPerms extension。這不是 tokaRegions 已實作，也不是所有現有功能已改完。

參考：[LuckPerms 使用與繼承](https://luckperms.net/wiki/Usage)、[Context](https://luckperms.net/wiki/Context)、[API 與離線載入](https://luckperms.net/wiki/Developer-API-Usage)、[解析優先序](https://luckperms.net/wiki/Advanced-Setup)、[Nukkit 設定](https://github.com/LuckPerms/LuckPerms/blob/master/nukkit/src/main/resources/config.yml)。能力拆分、角色圖與降級流程是本專案設計提案。