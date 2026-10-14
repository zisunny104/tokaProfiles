package dev.toka.profiles;
import cn.nukkit.plugin.PluginBase;
import dev.toka.profiles.storage.ProfileRepository;
import dev.toka.storage.*;
import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;
public final class Main extends PluginBase {
 private ProfileRepository profiles;
 private java.util.concurrent.CompletableFuture<?> migration;
 @Override public void onEnable(){saveDefaultConfig();try{Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(java.util.Locale.ROOT));prepareMigration(backend);var active=localPath(getConfig().getString("storage.active-directory","storage"));try{active=activationPath(backend);}catch(Exception error){getLogger().error("待啟用資料驗證失敗，繼續使用原資料來源",error);}profiles=ProfileRepository.open(new StoreConfig(backend,active,1),getConfig().getInt("storage.queue-capacity",128));getLogger().info("tokaProfiles 就緒："+profiles.snapshot().size()+" 筆玩家資料");startStaging(backend);}catch(Exception e){getLogger().error("tokaProfiles 儲存初始化失敗",e);getServer().getPluginManager().disablePlugin(this);}}
 private java.nio.file.Path localPath(String value){var root=getDataFolder().toPath().toAbsolutePath().normalize();var path=root.resolve(value).normalize();if(path.equals(root)||!path.startsWith(root))throw new IllegalArgumentException("路徑必須位於插件資料目錄內");for(var p=path;p!=null&&p.startsWith(root);p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))throw new IllegalArgumentException("不接受符號連結");return path;}
 private java.nio.file.Path activationPath(Backend backend)throws Exception {
  var active=localPath(getConfig().getString("storage.active-directory","storage"));
  if(!getConfig().getBoolean("staged.activate-next-restart",false))return active;
  if(!getConfig().getBoolean("staged.source-refreshed-after-stop",false))throw new IllegalStateException("切換前必須停服並重新匯出來源，再確認來源已更新");
  var stage=localPath(getConfig().getString("staged.directory","staged-migration"));
  var source=localPath(getConfig().getString("staged.source","legacy-playerData.json"));
  var verified=dev.toka.profiles.migration.StagedProfileMigration.verifyReady(stage,source,backend);
  getConfig().set("storage.active-directory",getDataFolder().toPath().toAbsolutePath().normalize().relativize(verified).toString());
  getConfig().set("staged.activate-next-restart",false);getConfig().set("staged.source-refreshed-after-stop",false);getConfig().set("staged.enabled",false);
  if(!getConfig().save())throw new IllegalStateException("切換設定保存失敗；未開啟新資料集");
  return verified;
 }
 private void startStaging(Backend backend){
  if(!getConfig().getBoolean("staged.enabled",false))return;
  try{var stage=localPath(getConfig().getString("staged.directory","staged-migration"));var active=localPath(getConfig().getString("storage.active-directory","storage"));if(stage.startsWith(active)||active.startsWith(stage))throw new IllegalArgumentException("搬遷與有效資料目錄必須獨立");
   var source=localPath(getConfig().getString("staged.source","legacy-playerData.json"));
   var evidence=new com.google.gson.JsonArray();String evidenceFile=getConfig().getString("staged.plan-evidence","");if(!evidenceFile.isBlank())evidence=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(localPath(evidenceFile))).getAsJsonArray();
   migration=dev.toka.profiles.migration.StagedProfileMigration.startAsync(source,backend,stage,getConfig().getInt("staged.batch-size",50),getConfig().getInt("staged.delay-ms",100),evidence);
   migration.whenComplete((value,error)->{if(error!=null)getLogger().error("背景搬遷停止；有效資料未切換",error);else getLogger().info("背景搬遷完成："+value+"；需停服更新來源與確認後才切換");});
  }catch(Exception error){getLogger().error("背景搬遷未啟動；有效資料未切換",error);}
 }
 private void prepareMigration(Backend backend)throws Exception {
  if(!getConfig().getBoolean("migration.enabled",false))return;
  if(!getConfig().getString("migration.stage","pending").equals("pending"))return;
  if(!getConfig().getBoolean("migration.confirm",false))throw new IllegalStateException("搬遷尚未確認；不建立空白資料");
  var folder=getDataFolder().toPath().toAbsolutePath().normalize();
  var source=folder.resolve(getConfig().getString("migration.source","legacy-playerData.json")).normalize();
  if(!source.startsWith(folder)||java.nio.file.Files.isSymbolicLink(source))throw new IllegalStateException("來源必須在插件資料目錄內");
  String expected=getConfig().getString("migration.source-sha256","");
  String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(source)));
  if(!expected.matches("[a-fA-F0-9]{64}")||!actual.equalsIgnoreCase(expected))throw new IllegalStateException("來源雜湊未確認或不符");
  dev.toka.profiles.migration.ProfileMigration.importFile(source,backend,folder.resolve("storage"),folder.resolve("backups"));
  getConfig().set("migration.stage","imported");getConfig().set("migration.confirm",false);
  if(!getConfig().save())throw new IllegalStateException("匯入完成但設定保存失敗，請人工核對；禁止重複匯入");
 }
 public ProfileRepository profiles(){if(profiles==null||profiles.state()!=ProfileRepository.State.READY)throw new IllegalStateException("Profiles unavailable");return profiles;}
 public void openCityPass(Player player){var profile=profiles().find(player.getUniqueId());String content=profile.map(p->"玩家："+p.name()+"\n識別碼："+p.profileId()).orElse("尚未完成玩家資料搬遷。請聯絡管理員。");var form=new FormWindowSimple("城市通",content);form.addButton(new ElementButton("關閉"));player.showFormWindow(form,21080000);}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof Player player)){sender.sendMessage("此指令請在遊戲內使用。");return true;}if(!sender.hasPermission("tokaProfiles.self"))return false;openCityPass(player);return true;}
 @Override public void onDisable(){if(migration!=null&&!migration.isDone())migration.cancel(true);if(profiles!=null)try{profiles.close();}catch(Exception e){getLogger().error("Profile writer did not shut down cleanly",e);}}
}
