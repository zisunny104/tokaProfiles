package dev.toka.profiles.migration;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Plan-first offline resolution. Retired rows remain complete in a private history ledger. */
public final class PlanResolution {
 private PlanResolution(){}
 public record Result(JsonArray active,JsonArray history,JsonArray pending){ }
 private static String uuid(JsonObject r,String key){try{String raw=r.get(key).getAsString();UUID u=UUID.fromString(raw);return u.toString().equalsIgnoreCase(raw)?u.toString():null;}catch(RuntimeException e){return null;}}
 private static String text(JsonObject r,String key){return r.has(key)&&!r.get(key).isJsonNull()?r.get(key).getAsString():"";}
 public static Result resolve(JsonArray rows,JsonArray plan){
  var accounts=new TreeMap<String,JsonObject>();var names=new HashMap<String,Set<String>>();
  for(var element:plan){var p=element.getAsJsonObject();String u=uuid(p,"uuid"),name=text(p,"name");if(u==null||name.isBlank())throw new IllegalArgumentException("Invalid Plan identity");if(accounts.putIfAbsent(u,p)!=null)throw new IllegalArgumentException("Duplicate Plan UUID");names.computeIfAbsent(name.toLowerCase(Locale.ROOT),ignored->new HashSet<>()).add(u);}
  var groups=new TreeMap<String,List<JsonObject>>();var active=new JsonArray();var history=new JsonArray();var pending=new JsonArray();var ids=new HashSet<String>();
  for(var element:rows){var row=element.getAsJsonObject();String id=row.get("id").getAsBigDecimal().toBigIntegerExact().toString();if(!ids.add(id))throw new IllegalArgumentException("Duplicate legacy ID");String u=uuid(row,"UUID");if(u==null||!accounts.containsKey(u)){var matches=names.getOrDefault(text(row,"name").toLowerCase(Locale.ROOT),Set.of());u=matches.size()==1?matches.iterator().next():null;}if(u==null){active.add(row.deepCopy());continue;}groups.computeIfAbsent(u,ignored->new ArrayList<>()).add(row);}
  for(var group:groups.entrySet()){
   String u=group.getKey(),name=text(accounts.get(u),"name");var originals=group.getValue();JsonObject winner=null;String rule="single-source";
   if(originals.size()==1)winner=originals.get(0);
   else{
    var exact=originals.stream().filter(r->name.equals(text(r,"name"))&&u.equals(uuid(r,"UUID"))).toList();
    if(exact.size()==1){winner=exact.get(0);rule="plan-uuid-and-exact-current-name";}
    else{var direct=originals.stream().filter(r->u.equals(uuid(r,"UUID"))).toList();if(direct.size()==1){winner=direct.get(0);rule="unique-plan-uuid-source";}}
    if(winner==null){var baseline=originals.get(0).deepCopy();baseline.remove("id");baseline.remove("lastLogoutTime");boolean equal=true;for(var r:originals){var copy=r.deepCopy();copy.remove("id");copy.remove("lastLogoutTime");if(!copy.equals(baseline)){equal=false;break;}}if(equal){winner=originals.stream().min(Comparator.comparing(r->r.get("id").getAsBigDecimal())).orElseThrow();rule="equivalent-payload-oldest-id";}}
   }
   if(winner==null){JsonObject item=new JsonObject();item.addProperty("planUUID",u);item.addProperty("reason","PLAN_DOES_NOT_SELECT_DIFFERING_LEGACY_ROWS");var refs=new JsonArray();for(var r:originals){active.add(r.deepCopy());refs.add(r.get("id"));}item.add("legacyIds",refs);pending.add(item);continue;}
   var current=winner.deepCopy();current.addProperty("UUID",u);current.addProperty("name",name);var aliases=new TreeSet<String>();if(winner.has("aliases"))for(var a:winner.getAsJsonArray("aliases"))aliases.add(a.getAsString());
   for(var r:originals)if(!text(r,"name").isBlank()&&!name.equals(text(r,"name")))aliases.add(text(r,"name"));var aliasJson=new JsonArray();aliases.forEach(aliasJson::add);current.add("aliases",aliasJson);active.add(current);
   for(var r:originals)if(r!=winner){var event=new JsonObject();event.addProperty("schema",1);event.addProperty("status","archived-pending-deletion");event.addProperty("legacyId",r.get("id").getAsString());event.addProperty("replacementLegacyId",winner.get("id").getAsString());event.addProperty("replacementUUID",u);event.addProperty("resolutionRule",rule);event.addProperty("businessValuesMerged",false);event.addProperty("productionDeletionPerformed",false);event.addProperty("referenceCheck","pending");event.addProperty("deletionEligible",false);event.add("original",r.deepCopy());history.add(event);}
  }
  return new Result(active,history,pending);
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("SOURCE.json PLAN_EVIDENCE.json NEW_PRIVATE_DIRECTORY");
  Path source=Path.of(args[0]).toRealPath(),plan=Path.of(args[1]).toRealPath(),out=Path.of(args[2]).toAbsolutePath().normalize();
  if(Files.exists(out,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(out.toString());
  if(Files.size(source)>64L*1024*1024||Files.size(plan)>64L*1024*1024)throw new IllegalArgumentException("Snapshot too large");
  byte[] original=Files.readAllBytes(source),evidence=Files.readAllBytes(plan);Result result=resolve(StagedProfileMigration.rows(original),JsonParser.parseString(new String(evidence,StandardCharsets.UTF_8)).getAsJsonArray());
  Files.createDirectories(out);if(Files.getFileStore(out).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(out,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  Files.write(out.resolve("original-source.json"),original,StandardOpenOption.CREATE_NEW);Files.write(out.resolve("plan-evidence.json"),evidence,StandardOpenOption.CREATE_NEW);
  JsonObject current=new JsonObject();current.addProperty("schema",1);current.addProperty("table","playerData");current.add("rows",result.active());
  write(out.resolve("active-source.json"),current);write(out.resolve("history.json"),result.history());write(out.resolve("pending.json"),result.pending());
  JsonObject manifest=new JsonObject();manifest.addProperty("sourceSha256",hash(original));manifest.addProperty("planSha256",hash(evidence));manifest.addProperty("sourceRows",StagedProfileMigration.rows(original).size());manifest.addProperty("activeRows",result.active().size());manifest.addProperty("archivedRows",result.history().size());manifest.addProperty("pendingGroups",result.pending().size());manifest.addProperty("productionWrites",false);write(out.resolve("manifest.json"),manifest);
  if(!Arrays.equals(original,Files.readAllBytes(source))||!Arrays.equals(evidence,Files.readAllBytes(plan)))throw new IllegalStateException("Source changed; do not activate");
  System.out.println("PLAN_RESOLUTION_PASS Active="+result.active().size()+" Archived="+result.history().size()+" PendingGroups="+result.pending().size()+" ProductionWrites=false");
 }
 private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 private static void write(Path path,JsonElement value)throws Exception{Files.writeString(path,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);if(!JsonParser.parseString(Files.readString(path)).equals(value))throw new IllegalStateException("Resolution readback mismatch");}
}
