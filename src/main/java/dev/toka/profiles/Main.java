package dev.toka.profiles;
import cn.nukkit.plugin.PluginBase;
import dev.toka.profiles.storage.ProfileRepository;
import dev.toka.storage.*;
import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;
import dev.toka.i18n.Messages;
public final class Main extends PluginBase implements cn.nukkit.event.Listener {
 private ProfileRepository profiles;
 private java.util.concurrent.CompletableFuture<?> migration;
 private Messages messages;
 private AutoCloseable plan;
 private dev.toka.profiles.legacy.LegacyBook book;
 private dev.toka.profiles.legacy.Inheritance inheritance;
 private static volatile Main running;
 private final dev.toka.profiles.command.SelfTitle selfTitle=new dev.toka.profiles.command.SelfTitle();
 private dev.toka.profiles.plan.PlanControl planControl;
 @Override public void onEnable(){saveDefaultConfig();suggest("profiles",
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"help"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),CommandParameter.newType("玩家或UUID",CommandParamType.STRING)},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),CommandParameter.newType("玩家或UUID",CommandParamType.STRING),CommandParameter.newEnum("稱號",new String[]{"clear"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),CommandParameter.newType("玩家或UUID",CommandParamType.STRING),CommandParameter.newType("稱號",CommandParamType.RAWTEXT)});
  DisplayLink.register(this,getConfig().getString("nametag.title-suffix","§6-"));
  messages=new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language","zh-TW"),line->getLogger().warning(line));Texts.use(messages);try{Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(java.util.Locale.ROOT));prepareMigration(backend);var active=localPath(getConfig().getString("storage.active-directory","storage"));try{active=activationPath(backend);}catch(Exception error){getLogger().error(messages.text("log.activationFailed"),error);}profiles=ProfileRepository.open(new StoreConfig(backend,active,1),getConfig().getInt("storage.queue-capacity",128));getLogger().info(messages.text("log.ready",profiles.snapshot().size()));startStaging(backend);startLegacy(backend);getServer().getPluginManager().registerEvents(this,this);refreshPlan();}catch(Exception e){getLogger().error(messages.text("log.storageInitFailed"),e);getServer().getPluginManager().disablePlugin(this);}}
 private void startLegacy(Backend backend)throws Exception {
  book=dev.toka.profiles.legacy.LegacyBook.open(new StoreConfig(backend,localPath("legacy"),1));
  inheritance=new dev.toka.profiles.legacy.Inheritance(profiles,book,task->getServer().getScheduler().scheduleTask(this,task),line->getLogger().info(line),line->getLogger().warning(line));
  running=this;
  var notes=getDataFolder().toPath().resolve("legacy-notes.json");
  var source=getDataFolder().toPath().resolve("legacy-players.json");
  if(java.nio.file.Files.isRegularFile(source)){
   var worker=new Thread(()->{try{getLogger().info(inheritance.importFile(source));java.nio.file.Files.move(source,source.resolveSibling("legacy-players.json.imported"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception error){getLogger().warning(messages.text("log.legacyImportFailed",String.valueOf(error)));}},"tokaProfiles-legacy-import");
   worker.setDaemon(true);worker.start();
  }
  if(java.nio.file.Files.isRegularFile(notes)){
   var worker=new Thread(()->{try{
     while(java.nio.file.Files.isRegularFile(source))Thread.sleep(500); // the players first: the notes go into their profiles
     getLogger().info(inheritance.importNotes(notes));java.nio.file.Files.move(notes,notes.resolveSibling("legacy-notes.json.imported"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }catch(Exception error){getLogger().warning(messages.text("log.legacyImportFailed",String.valueOf(error)));}},"tokaProfiles-legacy-notes");
   worker.setDaemon(true);worker.start();
  }
 }
 /** Every old name that has been taken over, name to account; empty while the plugin is not running. For plugins that replace old names. */
 public static java.util.Map<String,java.util.UUID> legacyResolved(){var self=running;return self==null||self.book==null?java.util.Map.of():self.book.resolved();}
 /** What the old record said about an account that took over a name: village, discordId, title. Empty when it has none. */
 public static java.util.Map<String,String> legacyDetail(java.util.UUID uuid){var self=running;if(self==null||self.book==null)return java.util.Map.of();return self.book.claimedBy(uuid).map(r->java.util.Map.of("name",r.name(),"village",r.village(),"discordId",r.discordId(),"title",r.title())).orElse(java.util.Map.of());}
 /** Called with (old name, account) each time an old name is taken over. Receivers also read legacyResolved() when they start. */
 public static void onLegacyResolved(java.util.function.BiConsumer<String,java.util.UUID> listener){var self=running;if(self!=null&&self.book!=null)self.book.listen(listener);}
 @cn.nukkit.event.EventHandler public void joined(cn.nukkit.event.player.PlayerJoinEvent e){
  if(inheritance==null||profiles==null)return;var player=e.getPlayer();
  inheritance.login(player.getUniqueId(),player.getName()).thenRun(()->rememberIdentity(player)).exceptionally(failure->{getLogger().warning(messages.text("log.loginFailed",player.getName(),String.valueOf(failure)));return null;});
 }
 /** The identities are kept in the profile, for the name tag of someone who is offline. */
 private void rememberIdentity(Player player){
  try{
   String now=DisplayLink.identities(player::hasPermission);
   profiles().find(player.getUniqueId()).filter(p->!now.equals(p.preferences().getOrDefault("identity",""))).ifPresent(p->profiles().put(p.withPreference("identity",now)));
  }catch(RuntimeException unavailable){/* the next login records it */}
 }
 @cn.nukkit.event.EventHandler(priority=cn.nukkit.event.EventPriority.MONITOR) public void left(cn.nukkit.event.player.PlayerQuitEvent e){if(inheritance!=null&&profiles!=null)rememberIdentity(e.getPlayer());}
 /** /profiles note <player or UUID> [text|clear]: a free text about a player, for administrators. */
 private void noteCommand(CommandSender sender,String[] args){
  if(!sender.hasPermission("tokaProfiles.note")){sender.sendMessage(messages.text("err.noNotePermission"));return;}
  ProfileRepository repository;try{repository=profiles();}catch(IllegalStateException e){sender.sendMessage(messages.text("err.profilesNotReady"));return;}
  if(args.length<2){sender.sendMessage(messages.text("note.usage"));return;}
  java.util.Optional<dev.toka.profiles.model.Profile> found;
  try{found=repository.find(java.util.UUID.fromString(args[1]));}catch(IllegalArgumentException notUuid){found=repository.findByName(args[1]);}
  if(found.isEmpty()){sender.sendMessage(messages.text("err.legacy.noPlayer"));return;}
  var profile=found.get();
  if(args.length==2){var text=profile.preferences().getOrDefault("note","");sender.sendMessage(text.isBlank()?messages.text("note.none",profile.name()):messages.text("note.show",profile.name(),text));return;}
  String text=args[2].equalsIgnoreCase("clear")&&args.length==3?"":String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length));
  if(text.length()>1000){sender.sendMessage(messages.text("note.tooLong"));return;}
  repository.put(profile.withPreference("note",text)).whenComplete((done,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?messages.text(text.isEmpty()?"note.cleared":"note.saved",profile.name()):String.valueOf(failure))));
 }
 private void legacyCommand(CommandSender sender,String[] args){
  if(!sender.hasPermission("tokaProfiles.legacy")){sender.sendMessage(messages.text("err.noLegacyPermission"));return;}
  if(book==null){sender.sendMessage(messages.text("err.profilesNotReady"));return;}
  if(args.length>=3&&args[1].equalsIgnoreCase("confirm")){
   java.util.UUID id;try{id=java.util.UUID.fromString(args[args.length-1]);}catch(IllegalArgumentException notUuid){var found=profiles().findByName(args[args.length-1]);if(found.isEmpty()){sender.sendMessage(messages.text("err.legacy.noPlayer"));return;}id=found.get().uuid();}
   String name=String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length-1));
   inheritance.confirm(name,id).whenComplete((message,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?message:String.valueOf(failure))));return;
  }
  var waiting=book.waiting();
  sender.sendMessage(messages.text("legacy.summary",book.size(),book.unclaimed(),waiting.size()));
  for(var record:waiting)sender.sendMessage(messages.text("legacy.waiting",record.name(),record.disputed()));
 }
 private void suggest(String name,CommandParameter[]... forms){Command registered=(Command)getCommand(name);if(registered==null)return;registered.getCommandParameters().clear();for(int i=0;i<forms.length;i++)registered.addCommandParameters("choice"+i,forms[i]);}
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
   if(tag.equals("*")||(tag.equals("title")&&sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION))||(tag.equals("self")&&sender.hasPermission(dev.toka.profiles.command.SelfTitle.PERMISSION)))sender.sendMessage(line.substring(bar+1));
  }
 }
 public ProfileRepository profiles(){if(profiles==null||profiles.state()!=ProfileRepository.State.READY)throw new IllegalStateException("Profiles unavailable");return profiles;}
 public void openCityPass(Player player){var profile=profiles().find(player.getUniqueId());String content=profile.map(p->messages.text("ui.cityPass.content",p.name(),p.profileId())).orElse(messages.text("ui.cityPass.noData"));var form=new FormWindowSimple(messages.text("ui.cityPass.title"),content);form.addButton(new ElementButton(messages.text("ui.cityPass.close")));player.showFormWindow(form,21080000);}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(planControl!=null&&planControl.command(sender,args,"tokaProfiles.admin"))return true;
  if(args.length>0&&args[0].equalsIgnoreCase("help")){sendHelp(sender);return true;}
  if(args.length>0&&args[0].equalsIgnoreCase("legacy")){legacyCommand(sender,args);return true;}
  if(args.length>0&&args[0].equalsIgnoreCase("note")){noteCommand(sender,args);return true;}
  if(args.length>0&&(args[0].equalsIgnoreCase("title")||args[0].equalsIgnoreCase("titles"))){
   if(!sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION)){sender.sendMessage(messages.text("err.noTitlePermission"));return true;}
   ProfileRepository repository;try{repository=profiles();}catch(IllegalStateException e){sender.sendMessage(messages.text("err.profilesNotReady"));return true;}
   dev.toka.profiles.command.TitleAdmin.run(repository,java.util.Arrays.copyOfRange(args,1,args.length)).whenComplete((message,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?message:messages.text("err.title.notSaved",failure.getMessage()))));
   return true;
  }
  if(args.length>0&&args[0].equalsIgnoreCase("mytitle")){
   if(!(sender instanceof Player self)){sender.sendMessage(messages.text("err.playerOnly"));return true;}
   if(!sender.hasPermission(dev.toka.profiles.command.SelfTitle.PERMISSION)){sender.sendMessage(messages.text("err.noTitlePermission"));return true;}
   ProfileRepository repository;try{repository=profiles();}catch(IllegalStateException e){sender.sendMessage(messages.text("err.profilesNotReady"));return true;}
   var rules=new dev.toka.profiles.command.SelfTitle.Rules(getConfig().getInt("title.self.max-length",16),getConfig().getInt("title.self.cooldown-seconds",300)*1000L,getConfig().getStringList("title.self.blocked-words"));
   selfTitle.run(repository,self.getUniqueId(),sender.hasPermission(dev.toka.profiles.command.SelfTitle.COLOR_PERMISSION),sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION),java.util.Arrays.copyOfRange(args,1,args.length),rules,System.currentTimeMillis())
    .whenComplete((message,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?message:messages.text("err.title.notSaved",failure.getMessage()))));
   return true;
  }
  if(!(sender instanceof Player player)){sender.sendMessage(messages.text("err.playerOnly"));return true;}if(!sender.hasPermission("tokaProfiles.self"))return false;openCityPass(player);return true;}
 @Override public void onDisable(){running=null;closePlan();if(book!=null)try{book.close();}catch(Exception e){getLogger().warning(String.valueOf(e));}if(migration!=null&&!migration.isDone())migration.cancel(true);if(profiles!=null)try{profiles.close();}catch(Exception e){getLogger().error(messages.text("log.writerCloseFailed"),e);}}
 private void closePlan(){if(planControl!=null)planControl.registered(false);if(plan!=null)try{plan.close();}catch(Exception|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}finally{plan=null;}}
 private void refreshPlan(){closePlan();
  if(planControl==null)planControl=new dev.toka.profiles.plan.PlanControl(this,this::refreshPlan,()->java.util.Set.of("exists","title","count"),(key,args)->messages.text(key,args),"profiles");var plugin=getServer().getPluginManager().getPlugin("Plan");
  if(profiles==null||!planControl.shouldRegister())return;
  try{var fields=new java.util.HashSet<String>();for(String key:java.util.List.of("exists","title","count"))if(planControl.options().has(key))fields.add(key);
   var extension=new dev.toka.profiles.plan.PlanExtension(profiles,fields,key->messages.text(key));
   if(extension.register()){plan=extension;planControl.registered(true);getLogger().info(messages.text("plan.ready"));}else getLogger().warning(messages.text("plan.unsupported"));
  }catch(RuntimeException|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}}
 /** tokaDisplay may start after this plugin or be reloaded; give it the title part whenever it comes up. */
 @cn.nukkit.event.EventHandler public void displayEnable(cn.nukkit.event.plugin.PluginEnableEvent e){if("tokaDisplay".equals(e.getPlugin().getName()))DisplayLink.register(this,getConfig().getString("nametag.title-suffix","§6-"));}
 @cn.nukkit.event.EventHandler public void planEnable(cn.nukkit.event.plugin.PluginEnableEvent e){if("Plan".equals(e.getPlugin().getName()))refreshPlan();}
 @cn.nukkit.event.EventHandler public void planDisable(cn.nukkit.event.plugin.PluginDisableEvent e){if("Plan".equals(e.getPlugin().getName()))closePlan();} public String planStatusJson(){return planControl.statusJson();}
 public boolean configurePlan(String mode,String[] fields){return planControl.configure(mode,fields);}
}
