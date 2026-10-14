import cn.nukkit.plugin.PluginBase;
import com.google.gson.*;
import dev.toka.profiles.storage.ProfileCodec;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Private snapshot mirror only, never a production plugin. */
public final class MirrorProfilesProbe extends PluginBase {
 @Override public void onEnable(){getServer().getScheduler().scheduleDelayedTask(this,()->{try{
  var profiles=(dev.toka.profiles.Main)getServer().getPluginManager().getPlugin("tokaProfiles");
  var zero=(prj.toka.zero.Main)getServer().getPluginManager().getPlugin("zero");
  if(profiles==null||!profiles.isEnabled()||zero==null||!zero.isEnabled())throw new AssertionError("Mirror plugins unavailable");
  var plan=getServer().getPluginManager().getPlugin("Plan");if(plan==null||!plan.isEnabled())throw new AssertionError("Plan unavailable");
  var source=JsonParser.parseString(Files.readString(Path.of(System.getProperty("probe.source-file")))).getAsJsonObject().getAsJsonArray("rows");
  var expected=dev.toka.profiles.migration.IdentityAudit.inspect(source,JsonParser.parseString(Files.readString(Path.of(System.getProperty("probe.plan-file")))).getAsJsonArray());
  var documents=dev.toka.profiles.migration.ProfileMigration.prepare(expected.accepted());
  if(profiles.profiles().snapshot().size()!=documents.size())throw new AssertionError("Mirror count mismatch");
  for(var p:profiles.profiles().snapshot().values())if(!ProfileCodec.encode(p).equals(documents.get(ProfileCodec.key(p.uuid()))))throw new AssertionError("Mirror profile mismatch");
  int rows=prj.toka.zero.runtime.LegacyDatabase.playerDataTableDao.queryForAll().size();if(rows!=source.size())throw new AssertionError("Legacy mirror lost rows");
  var p=profiles.profiles().snapshot().values().iterator().next();var legacy=prj.toka.zero.player.playerdata.PlayerData.getPlayerData(p.uuid());if(legacy==null)throw new AssertionError("Legacy UUID lookup failed");
  String before=legacy.getTitle();zero.getConfig().set("titles.mode","profiles");
  prj.toka.zero.runtime.TitleBridge.write(p.uuid().toString(),"Mirror fixture title").get(5,TimeUnit.SECONDS);
  if(!"Mirror fixture title".equals(legacy.getTitle()))throw new AssertionError("Title authority switch failed");
  zero.getConfig().set("titles.mode","legacy");prj.toka.zero.player.playerdata.PlayerData.reload();
  if(!java.util.Objects.equals(before,prj.toka.zero.player.playerdata.PlayerData.getPlayerData(p.uuid()).getTitle()))throw new AssertionError("Legacy title changed");
  getLogger().info("MIRROR_PROFILES_PASS Source="+rows+" Verified="+documents.size()+" Conflicts="+expected.conflicts().size()+" NoProductionWrites=true");
 }catch(Throwable e){getLogger().error("MIRROR_PROFILES_FAIL",e);}finally{getServer().shutdown();}},12);}
}
