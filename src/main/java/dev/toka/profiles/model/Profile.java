package dev.toka.profiles.model;

import java.util.*;
public final class Profile {
 private final UUID uuid; private final String name,legacyId; private final Set<String> aliases; private final Map<String,String> preferences;
 public Profile(UUID uuid,String name,Set<String> aliases,Map<String,String> preferences,String legacyId) {
  this.uuid=Objects.requireNonNull(uuid,"UUID required"); this.name=validName(name);
  var copy=new LinkedHashSet<String>(); for(String alias:Objects.requireNonNull(aliases)) copy.add(validName(alias)); this.aliases=Collections.unmodifiableSet(copy);
  var prefs=new TreeMap<String,String>(); Objects.requireNonNull(preferences).forEach((k,v)->prefs.put(Objects.requireNonNull(k),Objects.requireNonNull(v))); this.preferences=Collections.unmodifiableMap(prefs);
  if(legacyId!=null && legacyId.isBlank()) throw new IllegalArgumentException("Blank legacyId"); this.legacyId=legacyId;
 }
 private static String validName(String name) { if(name==null||name.isBlank()||!name.equals(name.trim())) throw new IllegalArgumentException("Invalid name"); return name; }
 public UUID uuid(){return uuid;} public UUID profileId(){return uuid;} public String name(){return name;} public Set<String> aliases(){return aliases;} public Map<String,String> preferences(){return preferences;} public Optional<String> legacyId(){return Optional.ofNullable(legacyId);}
}

