package dev.toka.profiles.legacy;

import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.ProfileRepository;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Keeps a profile for every account that logs in, and hands an old name to the account that is entitled to it. The claim is made on
 * the server thread ({@code main}), because that is where the receivers of the news expect to hear it.
 */
public final class Inheritance {
    /** The preference that holds a free text about the player, written by administrators. */
    public static final String NOTE = "note";
    private final ProfileRepository profiles;
    private final LegacyBook book;
    private final Executor main;
    private final Consumer<String> info, warn;

    public Inheritance(ProfileRepository profiles, LegacyBook book, Executor main, Consumer<String> info, Consumer<String> warn) {
        this.profiles = profiles;
        this.book = book;
        this.main = main;
        this.info = info;
        this.warn = warn;
    }

    public CompletableFuture<Void> login(UUID uuid, String name) {
        Profile existing = profiles.find(uuid).orElse(null);
        // A player who has never been here reads the welcome pages; one who comes back under an old name does not.
        boolean returning = book.find(name).filter(r -> r.claimedBy() == null).isPresent();
        Profile current = existing == null ? new Profile(uuid, name, Set.of(), returning ? Map.of() : Map.of("welcome", "pending"), null) : existing.name().equals(name) ? existing : existing.withName(name);
        CompletableFuture<Void> saved = current == existing ? CompletableFuture.completedFuture(null) : profiles.put(current);
        return saved.thenComposeAsync(done -> resolve(uuid, name), main);
    }

    private CompletableFuture<Void> resolve(UUID uuid, String name) {
        Optional<LegacyRecord> record = book.find(name);
        if (record.isEmpty()) return CompletableFuture.completedFuture(null);
        try {
            switch (LegacyBook.judge(record.get(), name, uuid)) {
                case CLAIM: return adopt(record.get(), uuid);
                case CONFIRM_NEEDED:
                    if (book.dispute(record.get().name(), uuid)) warn.accept("舊名稱 " + record.get().name() + " 被 " + uuid + " 使用，但名稱曾對應其他帳號，需管理員確認：/profile legacy confirm " + record.get().name() + " " + uuid);
                    return CompletableFuture.completedFuture(null);
                default: return CompletableFuture.completedFuture(null);
            }
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Takes what the old record knew into the profile, then makes the name mean this account. */
    private CompletableFuture<Void> adopt(LegacyRecord record, UUID uuid) {
        Profile profile = profiles.find(uuid).orElseThrow();
        Profile merged = profile.title().isEmpty() && !record.title().isBlank() && record.title().length() <= 255 ? profile.withTitle(record.title()) : profile;
        Profile noted = !record.note().isBlank() && profile.preferences().getOrDefault(NOTE, "").isBlank() ? merged.withPreference(NOTE, record.note()) : merged;
        Profile withId = profile.legacyId().isEmpty() && !record.legacyId().isBlank() ? noted.withLegacyId(record.legacyId()) : noted;
        return profiles.put(withId).exceptionallyComposeAsync(failure -> profiles.put(noted), main).thenRunAsync(() -> {
            try {
                if (book.claim(record.name(), uuid)) info.accept("舊名稱 " + record.name() + " 已由 " + uuid + " 取回");
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, main);
    }

    /** An administrator decides: the old name means this account (which must have a profile, so it has logged in once). */
    public CompletableFuture<String> confirm(String name, UUID uuid) {
        Optional<LegacyRecord> record = book.find(name);
        if (record.isEmpty()) return CompletableFuture.completedFuture("找不到舊名稱 " + name);
        if (record.get().claimedBy() != null) return CompletableFuture.completedFuture("舊名稱 " + record.get().name() + " 已由 " + record.get().claimedBy() + " 取回");
        if (profiles.find(uuid).isEmpty()) return CompletableFuture.completedFuture("這個帳號還沒有玩家檔案（需要先登入一次）：" + uuid);
        return adopt(record.get(), uuid).thenApply(done -> "舊名稱 " + record.get().name() + " 已指定給 " + uuid);
    }
}
