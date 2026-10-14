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
 @Override public void onEnable(){saveDefaultConfig();try{Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(java.util.Locale.ROOT));prepareMigration(backend);profiles=ProfileRepository.open(new StoreConfig(backend,getDataFolder().toPath().resolve("storage"),1),getConfig().getInt("storage.queue-capacity",128));getLogger().info("tokaProfiles 就緒："+profiles.snapshot().size()+" 筆玩家資料");}catch(Exception e){getLogger().error("tokaProfiles 儲存初始化失敗",e);getServer().getPluginManager().disablePlugin(this);}}
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
 @Override public void onDisable(){if(profiles!=null)try{profiles.close();}catch(Exception e){getLogger().error("Profile writer did not shut down cleanly",e);}}
}
