import cn.nukkit.plugin.PluginBase;
import net.luckperms.api.*;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import java.util.UUID;

/** Synthetic users only: test resolved permissions, inheritance and target contexts. */
public final class PermissionSemanticsProbe extends PluginBase {
 @Override public void onEnable(){new Thread(()->{try{
  var api=LuckPermsProvider.get();var base=api.getGroupManager().createAndLoadGroup("fixture-region-base").join();
  base.data().add(Node.builder("fixture.region.build").value(true).build());api.getGroupManager().saveGroup(base).join();
  var shared=api.getGroupManager().createAndLoadGroup("fixture-region-shared").join();
  shared.data().add(InheritanceNode.builder(base).build());api.getGroupManager().saveGroup(shared).join();
  var user=api.getUserManager().loadUser(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),"PermFixture").join();
  user.data().add(InheritanceNode.builder(shared).context(ImmutableContextSet.builder().add("world","fixture-a").add("toka-region","region-a").build()).build());
  user.data().add(Node.builder("fixture.global.*").value(true).build());
  user.data().add(Node.builder("fixture.global.delete").value(false).build());api.getUserManager().saveUser(user).join();user.getCachedData().invalidate();
  var a=QueryOptions.contextual(ImmutableContextSet.builder().add("world","fixture-a").add("toka-region","region-a").build());
  var b=QueryOptions.contextual(ImmutableContextSet.builder().add("world","fixture-a").add("toka-region","region-b").build());
  var wrongWorld=QueryOptions.contextual(ImmutableContextSet.builder().add("world","fixture-b").add("toka-region","region-a").build());
  if(!user.getCachedData().getPermissionData(a).checkPermission("fixture.region.build").asBoolean())throw new AssertionError("Scoped inheritance missing");
  if(user.getCachedData().getPermissionData(b).checkPermission("fixture.region.build").asBoolean())throw new AssertionError("Region permission leaked");
  if(user.getCachedData().getPermissionData(wrongWorld).checkPermission("fixture.region.build").asBoolean())throw new AssertionError("World permission leaked");
  if(user.getCachedData().getPermissionData(a).checkPermission("fixture.global.delete").asBoolean())throw new AssertionError("Explicit deny ignored");
  if(user.getCachedData().getPermissionData(a).checkPermission("fixture.never-set").asBoolean())throw new AssertionError("Undefined granted");
  getLogger().info("PERMISSION_SEMANTICS_PASS inheritance target-region target-world explicit-deny undefined");
 }catch(Throwable e){getLogger().error("PERMISSION_SEMANTICS_FAIL",e);}},"permission-fixture").start();}
}
