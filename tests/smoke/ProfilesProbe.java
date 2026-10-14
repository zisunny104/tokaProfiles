import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.concurrent.TimeUnit;
import dev.toka.profiles.model.Profile;
public final class ProfilesProbe extends PluginBase {
 @Override public void onEnable(){getServer().getScheduler().scheduleDelayedTask(this,()->{try{
  var plugin=(dev.toka.profiles.Main)getServer().getPluginManager().getPlugin("tokaProfiles");
  if(plugin==null||!plugin.isEnabled())throw new AssertionError("Profiles not loaded");
  var plan=getServer().getPluginManager().getPlugin("Plan");
  if(plan!=null){if(!plan.isEnabled())throw new AssertionError("Plan failed to enable");var lp=getServer().getPluginManager().getPlugin("LuckPerms");if(lp==null||!lp.isEnabled())throw new AssertionError("LuckPerms failed to enable");getLogger().info("PLAN_COEXISTENCE_PASS Plan="+plan.getDescription().getVersion());}
  if(Boolean.getBoolean("probe.activation")){var staged=plugin.profiles().find(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).orElseThrow();if(!"Staged title".equals(staged.title()))throw new AssertionError("Staged activation failed");getLogger().info("STAGED_ACTIVATION_PASS");}
  var zero=(prj.toka.zero.Main)getServer().getPluginManager().getPlugin("zero");
  UUID uuid=UUID.fromString("00440044-0044-0044-0044-004400440044");
  prj.toka.zero.player.playerdata.PlayerDataLib.addDataTable("Fixture",uuid.toString(),0,0,"v1");
  prj.toka.zero.player.playerdata.PlayerData.reload();
  var legacy=prj.toka.zero.player.playerdata.PlayerData.getPlayerData(uuid);
  if(legacy==null)throw new AssertionError("Legacy fixture missing");
  plugin.profiles().put(new Profile(uuid,"Fixture",Set.of("FormerFixture"),Map.of("firstWindowDate","20261014"),Integer.toString(legacy.getId()))).get(5,TimeUnit.SECONDS);
  zero.getConfig().set("profiles.mode","shadow");
  String result=prj.toka.zero.runtime.ProfilesBridge.audit(prj.toka.zero.player.playerdata.PlayerData.getAllPlayerData());
  if(!result.contains("相符 1，缺少或差異 0"))throw new AssertionError(result);
  if(!plugin.profiles().findByName("FORMERFIXTURE").orElseThrow().uuid().equals(uuid))throw new AssertionError("Name index failed");
  legacy.setTitle("Legacy title");
  zero.getConfig().set("titles.mode","profiles");
  prj.toka.zero.runtime.TitleBridge.write(uuid.toString(),"Profiles title").get(5,TimeUnit.SECONDS);
  if(!"Profiles title".equals(legacy.getTitle()))throw new AssertionError("Title bridge read failed");
  var update=prj.toka.zero.player.playerdata.PlayerDataLib.class.getDeclaredMethod("updateDataTable",prj.toka.zero.player.playerdata.PlayerData.class);update.setAccessible(true);update.invoke(null,legacy);
  zero.getConfig().set("titles.mode","legacy");
  prj.toka.zero.player.playerdata.PlayerData.reload();
  if(!"Legacy title".equals(prj.toka.zero.player.playerdata.PlayerData.getPlayerData(uuid).getTitle()))throw new AssertionError("Profiles overwrote legacy SQL title");
  zero.getConfig().set("titles.mode","profiles");
  getServer().getPluginManager().disablePlugin(plugin);
  if(prj.toka.zero.runtime.TitleBridge.read(uuid.toString())!=null)throw new AssertionError("Unavailable Profiles used stale title");
  if(!zero.isEnabled())throw new AssertionError("Title dependency disabled zero");
  zero.getConfig().set("permissions.mode","disabled");
  if(prj.toka.zero.runtime.permissions.PermissionBridge.legacyTop(legacy))throw new AssertionError("disabled granted");
  zero.getConfig().set("permissions.mode","luckperms");
  if(prj.toka.zero.runtime.permissions.PermissionBridge.legacyHigh(legacy))throw new AssertionError("Offline LP fallback granted");
  getLogger().info("PROFILES_CORE_PASS shadow identity-index permissions-fail-closed");
 }catch(Throwable error){getLogger().error("PROFILES_CORE_FAIL",error);}finally{getServer().shutdown();}},12);}
}
