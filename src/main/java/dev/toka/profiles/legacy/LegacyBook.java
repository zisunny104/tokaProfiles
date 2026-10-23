package dev.toka.profiles.legacy;

import com.google.gson.*;
import dev.toka.storage.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Every name the old server knew, and which account has taken each one over. This is the only place that decides who an old name is:
 * other plugins only hear "name N is now UUID U" and replace the name; they never match names themselves.
 * A name is taken over automatically when it is the exact name and no other account was ever seen with it; anything else waits for
 * an administrator.
 */
public final class LegacyBook implements AutoCloseable {
    public enum Verdict { CLAIM, CONFIRM_NEEDED, NONE }

    private final DocumentStore store;
    private final Map<String, LegacyRecord> records = new HashMap<>();
    private final List<BiConsumer<String, UUID>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private LegacyBook(DocumentStore store) throws IOException {
        this.store = store;
        for (var entry : store.snapshot().entrySet()) records.put(entry.getKey(), LegacyRecord.fromJson(entry.getValue()));
    }

    public static LegacyBook open(StoreConfig config) throws IOException {
        DocumentStore store = DocumentStore.open(config);
        try { return new LegacyBook(store); }
        catch (IOException | RuntimeException failure) { try { store.close(); } catch (IOException ignored) { } throw failure; }
    }

    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    /** Control characters, a leading "@" and quote marks are dropped: the old tables hold names typed by hand. */
    public static String clean(String name) {
        String text = name.replaceAll("\\p{Cntrl}", "").strip();
        if (text.startsWith("@")) text = text.substring(1);
        return text.replaceAll("^['\"]+|['\"]+$", "").strip();
    }

    /** The exact name and nobody else ever seen with it: taken over at once. Another account seen with the name needs an administrator. */
    public static Verdict judge(LegacyRecord record, String loginName, UUID login) {
        if (record.claimedBy() != null) return Verdict.NONE;
        if (record.uuid() != null) return record.uuid().equals(login) ? Verdict.CLAIM : Verdict.NONE;
        if (!record.name().equals(loginName)) return Verdict.CONFIRM_NEEDED;
        Set<UUID> others = new HashSet<>(record.evidence());
        others.remove(login);
        return others.isEmpty() ? Verdict.CLAIM : Verdict.CONFIRM_NEEDED;
    }

    /** Adds the names not known yet and returns them; rows are the old player table ({@code name, id, UUID, title, village, discordId, evidence}). */
    public synchronized List<LegacyRecord> importRows(JsonArray rows) throws IOException {
        List<LegacyRecord> added = new ArrayList<>();
        Map<String, JsonObject> documents = new LinkedHashMap<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String name = clean(LegacyRecord.text(row, "name"));
            if (name.isEmpty() || records.containsKey(key(name))) continue;
            UUID uuid = null;
            try { String raw = LegacyRecord.text(row, "UUID"); if (!raw.isBlank()) uuid = UUID.fromString(raw.strip()); } catch (IllegalArgumentException none) { /* never had one */ }
            Set<UUID> evidence = new LinkedHashSet<>();
            if (row.has("evidence")) for (JsonElement e : row.getAsJsonArray("evidence")) { try { evidence.add(UUID.fromString(e.getAsString())); } catch (IllegalArgumentException skip) { } }
            if (uuid != null) evidence.add(uuid);
            LegacyRecord record = new LegacyRecord(name, LegacyRecord.text(row, "id"), uuid, LegacyRecord.text(row, "title"), LegacyRecord.text(row, "village"),
                    LegacyRecord.text(row, "discordId"), evidence, Set.of(), null);
            records.put(key(name), record);
            documents.put(key(name), record.toJson());
            added.add(record);
        }
        if (!documents.isEmpty()) store.saveAll(documents);
        return added;
    }

    public synchronized Optional<LegacyRecord> find(String name) { return Optional.ofNullable(records.get(key(name))); }

    public synchronized int size() { return records.size(); }

    public synchronized List<LegacyRecord> waiting() {
        return records.values().stream().filter(r -> r.claimedBy() == null && !r.disputed().isEmpty()).sorted(Comparator.comparing(LegacyRecord::name)).toList();
    }

    public synchronized long unclaimed() { return records.values().stream().filter(r -> r.claimedBy() == null).count(); }

    /** Every name that has been taken over, and by whom. */
    public synchronized Map<String, UUID> resolved() {
        Map<String, UUID> out = new TreeMap<>();
        records.values().forEach(r -> { if (r.claimedBy() != null) out.put(r.name(), r.claimedBy()); });
        return out;
    }

    public synchronized Optional<LegacyRecord> claimedBy(UUID uuid) {
        return records.values().stream().filter(r -> uuid.equals(r.claimedBy())).findFirst();
    }

    /** Records that an account asked for a name it cannot have without an administrator; true the first time. */
    public synchronized boolean dispute(String name, UUID by) throws IOException {
        LegacyRecord record = records.get(key(name));
        if (record == null || record.disputed().contains(by)) return false;
        LegacyRecord next = record.disputedBy(by);
        store.save(key(name), next.toJson());
        records.put(key(name), next);
        return true;
    }

    /** The name now means this account. Saved first; listeners hear it only when it is saved, and a listener's failure stays its own. */
    public synchronized boolean claim(String name, UUID by) throws IOException {
        LegacyRecord record = records.get(key(name));
        if (record == null || record.claimedBy() != null) return false;
        LegacyRecord next = record.claimed(by);
        store.save(key(name), next.toJson());
        records.put(key(name), next);
        for (var listener : listeners) {
            try { listener.accept(next.name(), by); } catch (RuntimeException | LinkageError ignored) { /* a receiver catches up from resolved() */ }
        }
        return true;
    }

    /** For the import: the account was already known to the old data, so nobody needs telling; receivers read {@link #resolved()} when they start. */
    public synchronized void claimQuietly(String name, UUID by) throws IOException {
        LegacyRecord record = records.get(key(name));
        if (record == null || record.claimedBy() != null) return;
        LegacyRecord next = record.claimed(by);
        store.save(key(name), next.toJson());
        records.put(key(name), next);
    }

    public void listen(BiConsumer<String, UUID> listener) { listeners.add(listener); }

    @Override public synchronized void close() throws IOException { store.close(); }
}
