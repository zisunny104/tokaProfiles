package dev.toka.profiles.model;

import java.util.*;
public final class Profile {
 private final UUID uuid; private final String name,legacyId,title,affiliation; private final Set<String> aliases; private final Map<String,String> preferences;
 public Profile(UUID uuid,String name,Set<String> aliases,Map<String,String> preferences,String legacyId) {
  this(uuid,name,aliases,preferences,legacyId,"");
 }
 public Profile(UUID uuid,String name,Set<String> aliases,Map<String,String> preferences,String legacyId,String title) {
  this(uuid,name,aliases,preferences,legacyId,title,"");
 }
 /** @param affiliation the id of the region the player belongs to (a canonical UUID), or empty for none; a region's rules are not known here */
 public Profile(UUID uuid,String name,Set<String> aliases,Map<String,String> preferences,String legacyId,String title,String affiliation) {
  this.uuid=Objects.requireNonNull(uuid,"UUID required"); this.name=validName(name);
  var copy=new LinkedHashSet<String>(); for(String alias:Objects.requireNonNull(aliases)) copy.add(validName(alias)); this.aliases=Collections.unmodifiableSet(copy);
  var prefs=new TreeMap<String,String>(); Objects.requireNonNull(preferences).forEach((k,v)->prefs.put(Objects.requireNonNull(k),Objects.requireNonNull(v))); this.preferences=Collections.unmodifiableMap(prefs);
  if(legacyId!=null && legacyId.isBlank()) throw new IllegalArgumentException("Blank legacyId"); this.legacyId=legacyId;
  this.affiliation=validAffiliation(affiliation);
  this.title=Objects.requireNonNull(title,"Title required");if(title.length()>255)throw new IllegalArgumentException("Title too long");
 }
 private static String validAffiliation(String value) {
  if(value==null) throw new IllegalArgumentException("Affiliation required");
  if(value.isEmpty()) return value;
  if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Noncanonical affiliation");
  return value;
 }
 private static String validName(String name) { if(name==null||name.isBlank()||!name.equals(name.trim())) throw new IllegalArgumentException("Invalid name"); return name; }
 public UUID uuid(){return uuid;} public UUID profileId(){return uuid;} public String name(){return name;} public Set<String> aliases(){return aliases;} public Map<String,String> preferences(){return preferences;} public Optional<String> legacyId(){return Optional.ofNullable(legacyId);}
 public String title(){return title;}
 public Profile withTitle(String value){return new Profile(uuid,name,aliases,preferences,legacyId,value,affiliation);}
 public Profile withLegacyId(String value){return new Profile(uuid,name,aliases,preferences,value,title,affiliation);}
 public Profile withName(String value){var all=new LinkedHashSet<String>(aliases);if(!name.equals(value))all.add(name);all.remove(value);return new Profile(uuid,value,all,preferences,legacyId,title,affiliation);}
 public Profile withPreference(String key,String value){var copy=new TreeMap<String,String>(preferences);copy.put(key,value);return new Profile(uuid,name,aliases,copy,legacyId,title,affiliation);}
 public String affiliation(){return affiliation;}
 public Profile withAffiliation(String value){return new Profile(uuid,name,aliases,preferences,legacyId,title,value);}
}
