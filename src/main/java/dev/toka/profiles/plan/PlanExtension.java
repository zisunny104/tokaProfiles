package dev.toka.profiles.plan;
import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.*;
import com.djrapitops.plan.extension.annotation.*;
import com.djrapitops.plan.extension.builder.ExtensionDataBuilder;
import dev.toka.profiles.storage.ProfileRepository;
import java.util.*;
import java.util.function.Function;

/** Reads only the live repository's immutable index, never staging or name-resolution data. */
@PluginInfo(name="tokaProfiles",iconName="user")
public final class PlanExtension implements DataExtension,AutoCloseable {
 private final ProfileRepository profiles;private final Set<String> fields;private final Function<String,String> text;
 private volatile boolean active=true;private ExtensionService registered;
 public PlanExtension(ProfileRepository profiles,Set<String> fields,Function<String,String> text){
  this.profiles=profiles;this.fields=Set.copyOf(fields);this.text=text;
 }
 public boolean register(){if(!CapabilityService.getInstance().hasCapability("DATA_EXTENSION_BUILDER_API"))return false;
  ExtensionService service=ExtensionService.getInstance();if(service.register(this).isEmpty())return false;registered=service;return true;}
 public record Summary(boolean exists,String title){}
 public Summary snapshot(UUID id){
  if(!active || profiles.state()!=ProfileRepository.State.READY)throw new NotReadyException();
  var profile=profiles.find(id);return new Summary(profile.isPresent(),profile.map(p->p.title()).orElse(""));
 }
 @Override public CallEvents[] callExtensionMethodsOn(){return new CallEvents[]{CallEvents.PLAYER_JOIN,
  CallEvents.PLAYER_LEAVE,CallEvents.PLAYER_PERIODICAL,CallEvents.SERVER_EXTENSION_REGISTER,CallEvents.SERVER_PERIODICAL};}
 @DataBuilderProvider public ExtensionDataBuilder player(UUID id){var value=snapshot(id);var b=newExtensionDataBuilder();
  if(fields.contains("exists"))b.addValue(Boolean.class,b.valueBuilder(text.apply("plan.exists")).buildBoolean(value.exists()));
  if(fields.contains("title") && value.exists())b.addValue(String.class,b.valueBuilder(text.apply("plan.title")).buildString(value.title()));
  return b;}
 @DataBuilderProvider public ExtensionDataBuilder server(){
  if(!active || profiles.state()!=ProfileRepository.State.READY)throw new NotReadyException();var b=newExtensionDataBuilder();
  if(fields.contains("count"))b.addValue(Long.class,b.valueBuilder(text.apply("plan.count")).buildNumber((long)profiles.snapshot().size()));return b;}
 @Override public void close(){active=false;if(registered!=null){registered.unregister(this);registered=null;}}
}
