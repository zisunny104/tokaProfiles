package dev.toka.profiles.storage;
import com.google.gson.*;
import dev.toka.profiles.model.Profile;
import java.util.*;
public final class ProfileCodec {
 private ProfileCodec(){}
 public static String key(UUID uuid){return "profile-"+uuid;}
 public static JsonObject encode(Profile p){var o=new JsonObject();o.addProperty("schema",1);o.addProperty("uuid",p.uuid().toString());o.addProperty("profileId",p.profileId().toString());o.addProperty("name",p.name());o.addProperty("title",p.title());var a=new JsonArray();p.aliases().forEach(a::add);o.add("aliases",a);var prefs=new JsonObject();p.preferences().forEach(prefs::addProperty);o.add("preferences",prefs);p.legacyId().ifPresent(v->o.addProperty("legacyId",v));return o;}
 public static Profile decode(JsonObject o){if(!o.has("schema")||!o.get("schema").isJsonPrimitive()||!o.getAsJsonPrimitive("schema").isNumber()||!o.get("schema").getAsString().equals("1"))throw new IllegalArgumentException("Unsupported schema");UUID uuid=parseUuid(string(o,"uuid"));if(!uuid.equals(parseUuid(string(o,"profileId"))))throw new IllegalArgumentException("Identity mismatch");var aliases=new LinkedHashSet<String>();for(var a:o.getAsJsonArray("aliases")){String s=string(a);if(!aliases.add(s))throw new IllegalArgumentException("Duplicate alias");}var prefs=new TreeMap<String,String>();o.getAsJsonObject("preferences").entrySet().forEach(e->prefs.put(e.getKey(),string(e.getValue())));return new Profile(uuid,string(o,"name"),aliases,prefs,o.has("legacyId")?string(o,"legacyId"):null,o.has("title")?string(o,"title"):"");}
 private static UUID parseUuid(String s){UUID u=UUID.fromString(s);if(!u.toString().equalsIgnoreCase(s))throw new IllegalArgumentException("Noncanonical UUID");return u;}
 private static String string(JsonObject o,String key){return string(o.get(key));}private static String string(JsonElement e){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("String required");return e.getAsString();}
}

