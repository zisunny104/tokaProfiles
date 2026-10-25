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
        for (var entry : store.snapshot().values()) { LegacyRecord record = LegacyRecord.fromJson(entry); records.put(key(record.name()), record); }
    }

    public static LegacyBook open(StoreConfig config) throws IOException {
        DocumentStore store = DocumentStore.open(config);
        try { return new LegacyBook(store); }
        catch (IOException | RuntimeException failure) { try { store.close(); } catch (IOException ignored) { } throw failure; }
    }

    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    /** Names can hold anything, the store only plain characters: the document is kept under a hash of the name. */
    static String storeKey(String name) {
        try { return "n-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key(name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 20); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        store.save(storeKey(name), next.toJson());
        records.put(key(name), next);
        return true;
    }

    /** The name now means this account. Saved first; listeners hear it only when it is saved, and a listener's failure stays its own. */
    public synchronized boolean claim(String name, UUID by) throws IOException {
        LegacyRecord record = records.get(key(name));
        if (record == null || record.claimedBy() != null) return false;
        LegacyRecord next = record.claimed(by);
        store.save(storeKey(name), next.toJson());
        records.put(key(name), next);
        for (var listener : listeners) {
            try { listener.accept(next.name(), by); } catch (RuntimeException | LinkageError ignored) { /* a receiver catches up from resolved() */ }
        }
        return true;
    }

    public void listen(BiConsumer<String, UUID> listener) { listeners.add(listener); }

    @Override public synchronized void close() throws IOException { store.close(); }
}
