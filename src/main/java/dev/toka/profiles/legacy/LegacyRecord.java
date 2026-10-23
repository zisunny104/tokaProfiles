package dev.toka.profiles.legacy;

import com.google.gson.*;
import java.util.*;

/**
 * A player of the old server as it was recorded: the name, and whatever the old data knew about them. {@code uuid} is the account the
 * old data had for the name (null when it never had one); {@code evidence} are all UUIDs seen with this name anywhere. {@code claimedBy}
 * is the account that has taken the name over; once set, the name means that UUID everywhere.
 */
public record LegacyRecord(String name, String legacyId, UUID uuid, String title, String village, String discordId, Set<UUID> evidence, Set<UUID> disputed, UUID claimedBy, String note) {
    public LegacyRecord {
        evidence = Set.copyOf(evidence);
        disputed = Set.copyOf(disputed);
        note = note == null ? "" : note;
    }

    public LegacyRecord claimed(UUID by) { return new LegacyRecord(name, legacyId, uuid, title, village, discordId, evidence, Set.of(), by, note); }

    public LegacyRecord disputedBy(UUID by) {
        var all = new LinkedHashSet<>(disputed);
        all.add(by);
        return new LegacyRecord(name, legacyId, uuid, title, village, discordId, evidence, all, claimedBy, note);
    }

    public LegacyRecord withNote(String text) { return new LegacyRecord(name, legacyId, uuid, title, village, discordId, evidence, disputed, claimedBy, text); }

    public JsonObject toJson() {
        var out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("legacyId", legacyId);
        if (uuid != null) out.addProperty("uuid", uuid.toString());
        out.addProperty("title", title);
        out.addProperty("village", village);
        out.addProperty("discordId", discordId);
        out.add("evidence", ids(evidence));
        out.add("disputed", ids(disputed));
        if (claimedBy != null) out.addProperty("claimedBy", claimedBy.toString());
        if (!note.isEmpty()) out.addProperty("note", note);
        return out;
    }

    public static LegacyRecord fromJson(JsonObject o) {
        return new LegacyRecord(o.get("name").getAsString(), text(o, "legacyId"), o.has("uuid") ? UUID.fromString(o.get("uuid").getAsString()) : null,
                text(o, "title"), text(o, "village"), text(o, "discordId"), idSet(o, "evidence"), idSet(o, "disputed"),
                o.has("claimedBy") ? UUID.fromString(o.get("claimedBy").getAsString()) : null, text(o, "note"));
    }

    static String text(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }

    private static JsonArray ids(Set<UUID> set) {
        var array = new JsonArray();
        set.stream().map(UUID::toString).sorted().forEach(array::add);
        return array;
    }

    private static Set<UUID> idSet(JsonObject o, String key) {
        var out = new LinkedHashSet<UUID>();
        if (o.has(key)) for (var e : o.getAsJsonArray(key)) out.add(UUID.fromString(e.getAsString()));
        return out;
    }
}
