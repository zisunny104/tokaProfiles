package dev.toka.profiles;
import cn.nukkit.plugin.PluginBase;
import dev.toka.profiles.storage.ProfileRepository;
import dev.toka.storage.*;
import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import dev.toka.i18n.Messages;
public final class Main extends PluginBase implements cn.nukkit.event.Listener {
 private ProfileRepository profiles;
 private Messages messages;
 private AutoCloseable plan;
 private dev.toka.profiles.legacy.LegacyBook book;
 private dev.toka.profiles.legacy.Inheritance inheritance;
 private Welcome welcome;
 private CityPass cityPass;
 private Forms forms;
 private Overview overview;
 private AdminWindows adminWindows;
 private final java.util.List<Thread> workers=new java.util.concurrent.CopyOnWriteArrayList<>();
 private static volatile Main running;
 private final dev.toka.profiles.command.SelfTitle selfTitle=new dev.toka.profiles.command.SelfTitle();
 private dev.toka.profiles.plan.PlanControl planControl;
 @Override public void onEnable(){saveDefaultConfig();
  var who=CommandParameter.newType("玩家或UUID",CommandParamType.TARGET);var text=CommandParameter.newType("文字",CommandParamType.RAWTEXT);
  suggest("profile",
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"help"}),CommandParameter.newType("頁",true,CommandParamType.INT)},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"mytitle"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"mytitle"}),CommandParameter.newEnum("清除",new String[]{"clear"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"mytitle"}),CommandParameter.newType("稱號",CommandParamType.RAWTEXT)},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),who},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),who,CommandParameter.newEnum("清除",new String[]{"clear"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"title","titles"}),who,CommandParameter.newType("稱號",CommandParamType.RAWTEXT)},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"note"}),who},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"note"}),who,CommandParameter.newEnum("清除",new String[]{"clear"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"note"}),who,text},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"legacy"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"legacy"}),CommandParameter.newEnum("列表",new String[]{"list"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"legacy"}),CommandParameter.newEnum("確認",new String[]{"confirm"}),CommandParameter.newType("舊名稱",CommandParamType.STRING),who},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"admin"})},
  new CommandParameter[]{who},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"plan"}),CommandParameter.newEnum("Plan動作",new String[]{"status"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"plan"}),CommandParameter.newEnum("Plan動作",new String[]{"mode"}),CommandParameter.newEnum("模式",new String[]{"auto","enable","disable"})},
  new CommandParameter[]{CommandParameter.newEnum("動作",new String[]{"plan"}),CommandParameter.newEnum("Plan動作",new String[]{"fields"}),CommandParameter.newEnum("欄位",new String[]{"all","none","exists","title","count"})});
  DisplayLink.register(this,getConfig().getString("nametag.title-suffix","§6-"));
  messages=new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language","zh-TW"),line->getLogger().warning(line));Texts.use(messages);try{Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(java.util.Locale.ROOT));var active=localPath(getConfig().getString("storage.active-directory","storage"));profiles=ProfileRepository.open(new StoreConfig(backend,active,1),getConfig().getInt("storage.queue-capacity",128));getLogger().info(messages.text("log.ready",profiles.snapshot().size()));workers.add(offline().fillMissing());startLegacy(backend);getServer().getPluginManager().registerEvents(this,this);refreshPlan();}catch(Exception e){getLogger().error(messages.text("log.storageInitFailed"),e);getServer().getPluginManager().disablePlugin(this);}}
 private void startLegacy(Backend backend)throws Exception {
  book=dev.toka.profiles.legacy.LegacyBook.open(new StoreConfig(backend,localPath("legacy"),1));
  book.listen(this::announceLegacy);
  inheritance=new dev.toka.profiles.legacy.Inheritance(profiles,book,task->getServer().getScheduler().scheduleTask(this,task),line->getLogger().info(line),line->getLogger().warning(line));
  running=this;
  var tidy=new Thread(this::modernizeNotes,"tokaProfiles-notes-tidy");tidy.setDaemon(true);workers.add(tidy);tidy.start();
  welcome=new Welcome(this,messages);getServer().getPluginManager().registerEvents(welcome,this);
  forms=new Forms();getServer().getPluginManager().registerEvents(forms,this);
  overview=new Overview(this,forms,new SoftLinks(getServer()));adminWindows=new AdminWindows(this,forms,overview);
  cityPass=new CityPass(this,messages,forms,overview,adminWindows);getServer().getPluginManager().registerEvents(cityPass,this);
  for(Player online:getServer().getOnlinePlayers().values())cityPass.ensureItem(online);
  var notes=getDataFolder().toPath().resolve("legacy-notes.json");
  var source=getDataFolder().toPath().resolve("legacy-players.json");
  if(java.nio.file.Files.isRegularFile(source)){
   var worker=new Thread(()->{try{if(Thread.currentThread().isInterrupted())throw new InterruptedException();getLogger().info(inheritance.importFile(source));java.nio.file.Files.move(source,source.resolveSibling("legacy-players.json.imported"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}catch(Exception error){getLogger().warning(messages.text("log.legacyImportFailed",error.getClass().getSimpleName()));}},"tokaProfiles-legacy-import");
   worker.setDaemon(true);workers.add(worker);worker.start();
  }
  if(java.nio.file.Files.isRegularFile(notes)){
   var worker=new Thread(()->{try{
     while(java.nio.file.Files.isRegularFile(source))Thread.sleep(500); // the players first: the notes go into their profiles
     getLogger().info(inheritance.importNotes(notes));java.nio.file.Files.move(notes,notes.resolveSibling("legacy-notes.json.imported"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }catch(InterruptedException stopped){Thread.currentThread().interrupt();}catch(Exception error){getLogger().warning(messages.text("log.legacyImportFailed",error.getClass().getSimpleName()));}},"tokaProfiles-legacy-notes");
   worker.setDaemon(true);workers.add(worker);worker.start();
  }
 }
 // TODO: Reserved for other plugins that act on notes, called by reflection.
 /** The entries of the given type in a player's note, each as its fields (with "type" and "text" too). For plugins that act on notes, by reflection. */
 public static java.util.List<java.util.Map<String,String>> noteEntries(java.util.UUID uuid,String type){
  var self=running;if(self==null||self.profiles==null)return java.util.List.of();
  var out=new java.util.ArrayList<java.util.Map<String,String>>();
  self.profiles.find(uuid).ifPresent(p->{for(var entry:Notes.entries(p.preferences().getOrDefault("note",""))){if(!entry.type().equals(type))continue;var fields=new java.util.LinkedHashMap<String,String>(entry.fields());fields.put("type",entry.type());fields.put("text",entry.text());out.add(fields);}});
  return out;
 }
 // TODO: Reserved for other plugins that act on notes, called by reflection.
 /** Every player whose note has an entry of the type. */
 public static java.util.List<java.util.UUID> notedPlayers(String type){
  var self=running;if(self==null||self.profiles==null)return java.util.List.of();
  var out=new java.util.ArrayList<java.util.UUID>();
  for(var p:self.profiles.snapshot().values()){var note=p.preferences().get("note");if(note!=null&&Notes.entries(note).stream().anyMatch(e->e.type().equals(type)))out.add(p.uuid());}
  return out;
 }
 /** Every old name that has been taken over, name to account; empty while the plugin is not running. For plugins that replace old names. */
 public static java.util.Map<String,java.util.UUID> legacyResolved(){var self=running;return self==null||self.book==null?java.util.Map.of():self.book.resolved();}
 /** What the old record said about an account that took over a name: village, discordId, title. Empty when it has none. */
 public static java.util.Map<String,String> legacyDetail(java.util.UUID uuid){var self=running;if(self==null||self.book==null)return java.util.Map.of();return self.book.claimedBy(uuid).map(r->java.util.Map.of("name",r.name(),"village",r.village(),"discordId",r.discordId(),"title",r.title())).orElse(java.util.Map.of());}
 /** Fires {@link dev.toka.profiles.event.LegacyResolvedEvent} on the main thread (the takeover is normally made there already). */
 private void announceLegacy(String name,java.util.UUID uuid){
  Runnable fire=()->getServer().getPluginManager().callEvent(new dev.toka.profiles.event.LegacyResolvedEvent(name,uuid));
  if(getServer().isPrimaryThread())fire.run();else getServer().getScheduler().scheduleTask(this,fire);
 }
 @cn.nukkit.event.EventHandler public void joined(cn.nukkit.event.player.PlayerJoinEvent e){
  if(inheritance==null||profiles==null)return;var player=e.getPlayer();
  inheritance.login(player.getUniqueId(),player.getName()).thenRun(()->{rememberIdentity(player);getServer().getScheduler().scheduleDelayedTask(this,()->welcome.offer(player,profiles),40);}).exceptionally(failure->{getLogger().warning(messages.text("log.loginFailed",player.getName(),failure.getClass().getSimpleName()));return null;});
 }
 /** The identities are kept in the profile, for the name tag of someone who is offline. */
 private void rememberIdentity(Player player){
  try{
   String now=DisplayLink.identities(player::hasPermission);
   profiles().find(player.getUniqueId()).filter(p->!now.equals(p.preferences().getOrDefault("identity",""))).ifPresent(p->profiles().put(p.withPreference("identity",now)));
  }catch(RuntimeException unavailable){/* the next login records it */}
 }
 @cn.nukkit.event.EventHandler(priority=cn.nukkit.event.EventPriority.MONITOR) public void left(cn.nukkit.event.player.PlayerQuitEvent e){if(inheritance!=null&&profiles!=null)rememberIdentity(e.getPlayer());}
 /**
  * Adds a line to a player's note (an entry such as "donation amount=500 id=x", or plain text). For plugins that keep a record there,
  * by reflection; false when the player has no profile, the line is empty or the note would get too long.
  */
 public static java.util.concurrent.CompletableFuture<Boolean> appendNote(java.util.UUID uuid,String line){
  var self=running;
  if(self==null||self.profiles==null||line==null||line.isBlank())return java.util.concurrent.CompletableFuture.completedFuture(false);
  var found=self.profiles.find(uuid);
  if(found.isEmpty())return java.util.concurrent.CompletableFuture.completedFuture(false);
  String note=Notes.append(found.get().preferences().getOrDefault("note",""),line.strip().replace("\n"," "));
  if(note.length()>1000)return java.util.concurrent.CompletableFuture.completedFuture(false);
  return self.profiles.put(found.get().withPreference("note",note)).thenApply(done->true);
 }
 /** Notes written as plain text by an earlier version are rewritten as entries. */
 private void modernizeNotes(){
  if(profiles==null)return;
  for(var p:profiles.snapshot().values()){if(Thread.currentThread().isInterrupted())return;var note=p.preferences().get("note");if(note==null)continue;var now=Notes.modernized(note);if(!now.equals(note))try{profiles.put(p.withPreference("note",now)).get();}catch(Exception skip){/* tried again at the next start */}}
 }
 /** /profile note <player or UUID> [text|clear]: a free text about a player, for administrators. */
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
  repository.put(profile.withPreference("note",text)).whenComplete((done,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?messages.text(text.isEmpty()?"note.cleared":"note.saved",profile.name()):messages.text("err.title.notSaved",failure.getClass().getSimpleName()))));
 }
 private void legacyCommand(CommandSender sender,String[] args){
  if(!sender.hasPermission("tokaProfiles.legacy")){sender.sendMessage(messages.text("err.noLegacyPermission"));return;}
  if(book==null){sender.sendMessage(messages.text("err.profilesNotReady"));return;}
  if(args.length>=3&&args[1].equalsIgnoreCase("confirm")){
   java.util.UUID id;try{id=java.util.UUID.fromString(args[args.length-1]);}catch(IllegalArgumentException notUuid){var found=profiles().findByName(args[args.length-1]);if(found.isEmpty()){sender.sendMessage(messages.text("err.legacy.noPlayer"));return;}id=found.get().uuid();}
   String name=String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length-1));
   inheritance.confirm(name,id).whenComplete((message,failure)->getServer().getScheduler().scheduleTask(this,()->sender.sendMessage(failure==null?message:messages.text("err.title.notSaved",failure.getClass().getSimpleName()))));return;
  }
  var waiting=book.waiting();
  sender.sendMessage(messages.text("legacy.summary",book.size(),book.unclaimed(),waiting.size()));
  for(var record:waiting)sender.sendMessage(messages.text("legacy.waiting",record.name(),record.disputed()));
 }
 private void suggest(String name,CommandParameter[]... forms){Command registered=(Command)getCommand(name);if(registered==null)return;registered.getCommandParameters().clear();for(int i=0;i<forms.length;i++)registered.addCommandParameters("choice"+i,forms[i]);}
 private java.nio.file.Path localPath(String value){var root=getDataFolder().toPath().toAbsolutePath().normalize();var path=root.resolve(value).normalize();if(path.equals(root)||!path.startsWith(root))throw new IllegalArgumentException(messages.text("err.path.outside"));for(var p=path;p!=null&&p.startsWith(root);p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))throw new IllegalArgumentException(messages.text("err.path.symlink"));return path;}
 /** Lines of help.profiles look like "tag|text"; the tag names the permission a line needs. Chat help is paged: /profile help [page]. */
 private void sendHelp(CommandSender sender,String page){
  java.util.function.Predicate<String> allowed=tag->switch(tag){
   case "*"->true;
   case "self"->sender.hasPermission(dev.toka.profiles.command.SelfTitle.PERMISSION);
   case "title"->sender.hasPermission(dev.toka.profiles.command.TitleAdmin.PERMISSION);
   case "note"->sender.hasPermission("tokaProfiles.note");
   case "legacy"->sender.hasPermission("tokaProfiles.legacy");
   case "plan"->sender.hasPermission("tokaProfiles.admin");
   default->false;};
  for(String line:HelpPages.render(messages.lines("help.profiles",dev.toka.profiles.command.TitleAdmin.MAX_LENGTH),allowed,page,getConfig().getInt("help-lines-per-page",8),(shown,pages)->messages.text("help.footer",shown,pages)))sender.sendMessage(line);
 }
 Messages messages(){return messages;}
 dev.toka.profiles.legacy.LegacyBook book(){return book;}
 dev.toka.profiles.legacy.Inheritance inheritance(){return inheritance;}
 CityPass cityPass(){return cityPass;}
 /** The old record an account took over, as its fields; empty when it took none. */
 java.util.List<java.util.Map<String,String>> legacyOf(java.util.UUID uuid){var detail=legacyDetail(uuid);return detail.isEmpty()?java.util.List.of():java.util.List.of(detail);}
 private OfflineIdentities offline;
 /** Identities of people who are not online, from LuckPerms and the profile */
 public OfflineIdentities offline(){if(offline==null)offline=new OfflineIdentities(this);return offline;}
 public ProfileRepository profiles(){if(profiles==null||profiles.state()!=ProfileRepository.State.READY)throw new IllegalStateException("Profiles unavailable");return profiles;}
 public void openCityPass(Player player){if(cityPass!=null)cityPass.menu(player);}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(planControl!=null&&planControl.command(sender,args,"tokaProfiles.admin"))return true;
  if(args.length>0&&args[0].equalsIgnoreCase("help")){sendHelp(sender,args.length>1?args[1]:null);return true;}
  if(args.length>0&&args[0].equalsIgnoreCase("admin")){if(!(sender instanceof Player self)){sender.sendMessage(messages.text("err.playerOnly"));return true;}if(adminWindows==null||!AdminWindows.allowed(self)){sender.sendMessage(messages.text("err.noNotePermission"));return true;}adminWindows.open(self);return true;}
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
  if(!(sender instanceof Player player)){sender.sendMessage(messages.text("err.playerOnly"));return true;}if(!sender.hasPermission("tokaProfiles.self"))return false;
  if(args.length==1&&player.hasPermission(AdminWindows.NOTE)&&overview!=null){
   java.util.Optional<dev.toka.profiles.model.Profile> found;ProfileRepository repository;try{repository=profiles();}catch(IllegalStateException e){sender.sendMessage(messages.text("err.profilesNotReady"));return true;}
   try{found=repository.find(java.util.UUID.fromString(args[0]));}catch(IllegalArgumentException notUuid){found=repository.findByName(args[0]);}
   if(found.isEmpty()){sender.sendMessage(messages.text("err.legacy.noPlayer"));return true;}
   overview.open(player,found.get().uuid(),null);return true;
  }
  openCityPass(player);return true;}
 @Override public void onDisable(){running=null;DisplayLink.unregister(this);workers.forEach(Thread::interrupt);workers.clear();closePlan();if(book!=null)try{book.close();}catch(Exception e){getLogger().warning(messages.text("log.closeFailed",e.getClass().getSimpleName()));}if(profiles!=null)try{profiles.close();}catch(Exception e){getLogger().error(messages.text("log.writerCloseFailed"),e);}}
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
