# tokaProfiles

玩家共同身分與偏好服務，版本 **0.1.0**，Java 21／PM1E 4511。

## 最小拆分狀態

| 功能 | 狀態 |
| --- | --- |
| UUID、名稱／歷史名稱、固定 legacy ID 對照 | 已實作；拒絕衝突與缺少 UUID |
| 不可變 Profile、名稱索引、背景有界寫入 | 已實作，保存成功才發布新快照 |
| SQLite／JSON／YAML | 已實作，預設 SQLite；tokaStorage 私有打包，不需另裝插件 |
| 舊 playerData 匯入、來源備份、逐筆讀回 | 已實作；不覆寫既有目的地 |
| YAML 交接啟動匯入 | 明確 enabled／confirm／來源 SHA256 後才執行；不猜測、合併或重複匯入 |
| LuckPerms 基本身分計畫 | 已實作離線產生指令；不自動提升 OP 或網頁管理權 |
| 城市通新入口 `/profiles` | 僅個人資料；其他業務選單尚未搬遷 |
| zero 全部玩家資料接管 | **尚未完成**；zero 1.7.8 只支援 legacy／shadow 比對，不雙寫 |
| Plan 自訂資料展示、Discord 綁定、領地權限組 | 後續階段；不宣稱已接入 |

此版可以與舊資料服務並存測試，但不是「已搬完 zero」。不要移除原 MySQL／DbLib。住家、領地、餘額、有效權限、Discord ID、登入位置／IP 等不放入公開 Profile；完整舊 row 只留在私有搬遷備份。

## API 與資料責任

透過已啟用的 Main.profiles() 取得 repository；以 UUID 或名稱查詢，put(Profile) 回傳 CompletableFuture。一般讀取走索引，不同步查 SQL。呼叫者在背景保存完成後，將涉及遊戲物件的操作排回主執行緒；不要在玩家移動等熱路徑等待 future。

Profile API 提供 uuid/profileId/name/aliases/preferences/legacyId。profileId 等於既有已確認核心 UUID，不創造第二套身份。firstWindowDate 保留原 FirstWindow 的日期字串，不當作 boolean。稱號與職業暫留舊資料，因稱號購買涉及跨服務交易，未驗證前不先切換。

## 從 VPS 的 MySQL 匯出與驗證

先停服、備份 SQL／插件設定。export-jdbc 是只讀 SELECT；需要另在 classpath 放 MySQL JDBC driver（既有 DbLib JAR 若包含 driver 可使用）。密碼從互動 console 或 TOKA_DB_PASSWORD 讀取，不放命令參數或 Git。

```text
java -cp 'tokaProfiles-0.1.0.jar:DbLib_TK.jar' dev.toka.profiles.migration.ProfileMigration export-jdbc 'jdbc:mysql://localhost:3306/nukkit?useSSL=false' nukkit legacy-playerData.json
java -cp tokaProfiles-0.1.0.jar dev.toka.profiles.migration.ProfileMigration import legacy-playerData.json sqlite /ser/profiles-import/storage /ser/profiles-import/backups
java -cp tokaProfiles-0.1.0.jar dev.toka.profiles.migration.ProfileMigration plan-permissions legacy-playerData.json luckperms-plan.txt
```

上面 MySQL 匯出尚未在正式資料庫實測；匯入、指令計畫與三後端已使用虛構資料測試。空白 UUID、名稱大小寫碰撞、重複 ID、未知 qx 會停止，須先人工核對，不假造 UUID。snapshot 包含私人資料，不上傳 GitHub。

也可把 snapshot 放在 plugins/tokaProfiles/legacy-playerData.json，設定 migration.enabled=true、stage=pending、confirm=true 與 source-sha256，再啟動。新 storage 必須不存在。完成後 stage=imported、confirm=false；中斷或設定保存失敗要核對紀錄，不直接重跑。

## zero 與 LuckPerms 交接

zero 1.7.8 的 config.yml：

```yaml
profiles:
  mode: shadow # legacy 或 shadow；正式寫入接管尚未開放
permissions:
  mode: legacy # 匯入並驗證 LuckPerms 後才改 luckperms；也可 disabled
city-pass:
  mode: legacy # profiles 只開新資料選單；disabled 可隱藏未完成入口
features: {} # 例如 land: false，關閉尚未準備好的模組
```

`zero status` 提供資料比對結果。shadow 不改舊有效資料，避免雙寫。LuckPerms 模式對線上玩家檢查 toka.identity.admin／toka.identity.trusted；缺少 LuckPerms 不回退 qx，離線授權尚未實作。qx 仍保留顯示與 legacy 相容，後續能力節點搬完才刪除。城市通舊入口已標記棄用，切到 profiles 後不再打開原大型選單；尚未搬遷的業務功能維持各自指令或停用。

權限指令計畫保留 qx 0/1/2/3/4/8 各組，不合併身份；只給基本能力與 Plan 自己資料查閱。領地所有者／成員是每塊領地的關係，不等於 LuckPerms 全域群組；後續用區域上下文與專用節點接入，不能授予全服建築豁免。

## 驗證、編譯與更新

6 個 Profiles 單元／搬遷測試通過，三後端與 PM1E／zero shadow 的實際核心測試通過。沒有真玩家、正式 MySQL、Linux VPS、Plan 自訂展示或斷電驗證。SQLite／JSON／YAML 的格式限制沿用 tokaStorage；JSON／YAML 上限 16 MiB，頻繁資料建議 SQLite。

先於同層 tokaStorage 執行 build.ps1 install，再以 Java 21 執行本專案 build.ps1。產物 output/tokaProfiles-0.1.0.jar；output 不進版控。使用 Maven 預設 repository 以外的環境，透過 -Repository 指定相同快取。PM1E 核心需已安裝為 provided dependency。

新安裝可先加 Profiles，再更新 zero 1.7.8，保持 legacy 驗證；要搬遷必須走上述流程。既有服務不因安裝 Profiles 自動切換。正式切換與乾淨移除舊寫入仍是後續工作，不能只換 JAR 就刪舊資料。
