package dev.toka.profiles;
import cn.nukkit.plugin.PluginBase;
import dev.toka.profiles.storage.ProfileRepository;
import dev.toka.storage.*;
import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;
import dev.toka.i18n.Messages;
public final class Main extends PluginBase implements cn.nukkit.event.Listener {
 private ProfileRepository profiles;
 private java.util.concurrent.CompletableFuture<?> migration;
 private Messages messages;
 private AutoCloseable plan;
 private dev.toka.profiles.plan.PlanControl planControl;
 @Override public void onEnable(){saveDefaultConfig();messages=new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language","zh-TW"),line->getLogger().warning(line));Texts.use(messages);try{Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(java.util.Locale.ROOT));prepareMigration(backend);var active=localPath(getConfig().getString("storage.active-directory","storage"));try{active=activationPath(backend);}catch(Exception error){getLogger().error(messages.text("log.activationFailed"),error);}profiles=ProfileRepository.open(new StoreConfig(backend,active,1),getConfig().getInt("storage.queue-capacity",128));getLogger().info(messages.text("log.ready",profiles.snapshot().size()));startStaging(backend);getServer().getPluginManager().registerEvents(this,this);refreshPlan();}catch(Exception e){getLogger().error(messages.text("log.storageInitFailed"),e);getServer().getPluginManager().disablePlugin(this);}}
 private java.nio.file.Path localPath(String value){var root=getDataFolder().toPath().toAbsolutePath().normalize();var path=root.resolve(value).normalize();if(path.equals(root)||!path.startsWith(root))throw new IllegalArgumentException(messages.text("err.path.outside"));for(var p=path;p!=null&&p.startsWith(root);p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))throw new IllegalArgumentException(messages.text("err.path.symlink"));return path;}
 private java.nio.file.Path activationPath(Backend backend)throws Exception {
  var active=localPath(getConfig().getString("storage.active-directory","storage"));
  if(!getConfig().getBoolean("staged.activate-next-restart",false))return active;
  if(!getConfig().getBoolean("staged.source-refreshed-after-stop",false))throw new IllegalStateException(messages.text("err.activate.needRefresh"));
  var stage=localPath(getConfig().getString("staged.directory","staged-migration"));
  var source=localPath(getConfig().getString("staged.source","legacy-playerData.json"));
  var verified=dev.toka.profiles.migration.StagedProfileMigration.verifyReady(stage,source,backend);
  getConfig().set("storage.active-directory",getDataFolder().toPath().toAbsolutePath().normalize().relativize(verified).toString());
  getConfig().set("staged.activate-next-restart",false);getConfig().set("staged.source-refreshed-after-stop",false);getConfig().set("staged.enabled",false);
  try{ConfigEdit.apply(getDataFolder().toPath().resolve("config.yml"),java.util.Map.of("storage.active-directory",ConfigEdit.quote(getConfig().getString("storage.active-directory")),"staged.activate-next-restart","false","staged.source-refreshed-after-stop","false","staged.enabled","false"));}catch(java.io.IOException | RuntimeException saveError){throw new IllegalStateException(messages.text("err.activate.saveFailed"),saveError);}
  return verified;
 }
 private void startStaging(Backend backend){
  if(!getConfig().getBoolean("staged.enabled",false))return;
  try{var stage=localPath(getConfig().getString("staged.directory","staged-migration"));var active=localPath(getConfig().getString("storage.active-directory","storage"));if(stage.startsWith(active)||active.startsWith(stage))throw new IllegalArgumentException(messages.text("err.stage.notIndependent"));
   var source=localPath(getConfig().getString("staged.source","legacy-playerData.json"));
   var evidence=new com.google.gson.JsonArray();String evidenceFile=getConfig().getString("staged.plan-evidence","");if(!evidenceFile.isBlank())evidence=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(localPath(evidenceFile))).getAsJsonArray();
   migration=dev.toka.profiles.migration.StagedProfileMigration.startAsync(source,backend,stage,getConfig().getInt("staged.batch-size",50),getConfig().getInt("staged.delay-ms",100),evidence);
   migration.whenComplete((value,error)->{if(error!=null)getLogger().error(messages.text("log.stagedStopped"),error);else getLogger().info(messages.text("log.stagedDone",value));});
  }catch(Exception error){getLogger().error(messages.text("log.stagedNotStarted"),error);}
 }
 private void prepareMigration(Backend backend)throws Exception {
  if(!getConfig().getBoolean("migration.enabled",false))return;
  if(!getConfig().getString("migration.stage","pending").equals("pending"))return;
  if(!getConfig().getBoolean("migration.confirm",false))throw new IllegalStateException(messages.text("err.import.notConfirmed"));
  var folder=getDataFolder().toPath().toAbsolutePath().normalize();
  var source=folder.resolve(getConfig().getString("migration.source","legacy-playerData.json")).normalize();
  if(!source.startsWith(folder)||java.nio.file.Files.isSymbolicLink(source))throw new IllegalStateException(messages.text("err.import.sourceOutside"));
  String expected=getConfig().getString("migration.source-sha256","");
  String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(source)));
  if(!expected.matches("[a-fA-F0-9]{64}")||!actual.equalsIgnoreCase(expected))throw new IllegalStateException(messages.text("err.import.hashMismatch"));
  dev.toka.profiles.migration.ProfileMigration.importFile(source,backend,folder.resolve("storage"),folder.resolve("backups"));
  getConfig().set("migration.stage","imported");getConfig().set("migration.confirm",false);
  try{ConfigEdit.apply(getDataFolder().toPath().resolve("config.yml"),java.util.Map.of("migration.stage","imported","migration.confirm","false"));}catch(java.io.IOException | RuntimeException saveError){throw new IllegalStateException(messages.text("err.import.saveFailed"),saveError);}
 }
 /** Lines of help.profiles look like "tag|text": "*" is for everybody, "title" needs the title permission. */
 private void sendHelp(CommandSender sender){
  for(String line:messages.lines("help.profiles",dev.toka.profiles.command.TitleAdmin.MAX_LENGTH)){
   int bar=line.indexOf('|');if(bar<0){sender.sendMessage(line);continue;}
   String tag=line.substring(0,bar);
   if(tag.equals("*")||(tag.equals("title")&&sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION)))sender.sendMessage(line.substring(bar+1));
  }
 }
 public ProfileRepository profiles(){if(profiles==null||profiles.state()!=ProfileRepository.State.READY)throw new IllegalStateException("Profiles unavailable");return profiles;}
 public void openCityPass(Player player){var profile=profiles().find(player.getUniqueId());String content=profile.map(p->messages.text("ui.cityPass.content",p.name(),p.profileId())).orElse(messages.text("ui.cityPass.noData"));var form=new FormWindowSimple(messages.text("ui.cityPass.title"),content);form.addButton(new ElementButton(messages.text("ui.cityPass.close")));player.showFormWindow(form,21080000);}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(planControl!=null&&planControl.command(sender,args,"tokaProfiles.admin"))return true;
  if(args.length>0&&args[0].equalsIgnoreCase("help")){sendHelp(sender);return true;}
  if(args.length>0&&(args[0].equalsIgnoreCase("title")||args[0].equalsIgnoreCase("titles"))){
   if(!sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION)){sender.sendMessage(messages.text("err.noTitlePermission"));return true;}
   ProfileRepository repository;try{repository=profiles();}catch(IllegalStateException e){sender.sendMessage(messages.text("err.profilesNotReady"));return true;}
   dev.toka.profiles.command.TitleAdmin.run(repository,java.util.Arrays.copyOfRange(args,1,args.length)).whenComplete((message,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?message:messages.text("err.title.notSaved",failure.getMessage()))));
   return true;
  }
  if(!(sender instanceof Player player)){sender.sendMessage(messages.text("err.playerOnly"));return true;}if(!sender.hasPermission("tokaProfiles.self"))return false;openCityPass(player);return true;}
 @Override public void onDisable(){closePlan();if(migration!=null&&!migration.isDone())migration.cancel(true);if(profiles!=null)try{profiles.close();}catch(Exception e){getLogger().error(messages.text("log.writerCloseFailed"),e);}}
 private void closePlan(){if(planControl!=null)planControl.registered(false);if(plan!=null)try{plan.close();}catch(Exception|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}finally{plan=null;}}
 private void refreshPlan(){closePlan();
  if(planControl==null)planControl=new dev.toka.profiles.plan.PlanControl(this,this::refreshPlan,()->java.util.Set.of("exists","title","count"),(key,args)->messages.text(key,args),"profiles");var plugin=getServer().getPluginManager().getPlugin("Plan");
  if(profiles==null||!planControl.shouldRegister())return;
  try{var fields=new java.util.HashSet<String>();for(String key:java.util.List.of("exists","title","count"))if(planControl.options().has(key))fields.add(key);
   var extension=new dev.toka.profiles.plan.PlanExtension(profiles,fields,key->messages.text(key));
   if(extension.register()){plan=extension;planControl.registered(true);getLogger().info(messages.text("plan.ready"));}else getLogger().warning(messages.text("plan.unsupported"));
  }catch(RuntimeException|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}}
 @cn.nukkit.event.EventHandler public void planEnable(cn.nukkit.event.plugin.PluginEnableEvent e){if("Plan".equals(e.getPlugin().getName()))refreshPlan();}
 @cn.nukkit.event.EventHandler public void planDisable(cn.nukkit.event.plugin.PluginDisableEvent e){if("Plan".equals(e.getPlugin().getName()))closePlan();} public String planStatusJson(){return planControl.statusJson();}
 public boolean configurePlan(String mode,String[] fields){return planControl.configure(mode,fields);}
}
