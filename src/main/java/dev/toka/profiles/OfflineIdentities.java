package dev.toka.profiles;

import cn.nukkit.plugin.Plugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Who someone is when they are not online (a message that comes from Discord, a list of old players): read from the permissions
 * LuckPerms holds for them, remembered in memory and in the profile so the next look is free.
 */
final class OfflineIdentities {
    private final Main plugin;
    private final Map<UUID, String> known = new ConcurrentHashMap<>();

    OfflineIdentities(Main plugin) { this.plugin = plugin; }

    /** The identities as kept in the profile ("sponsor,linked"; empty is an ordinary player), or null when nobody has looked yet */
    String remembered(UUID id) {
        String own = known.get(id);
        if (own != null) return own;
        try {
            String kept = plugin.profiles().find(id).map(p -> p.preferences().get("identity")).orElse(null);
            if (kept != null) known.put(id, kept);
            return kept;
        } catch (RuntimeException unavailable) { return null; }
    }

    /** What is known now, else a short wait for LuckPerms (this is rare: each player is looked up once); null when it cannot be told */
    String lookup(UUID id) {
        String have = remembered(id);
        if (have != null) return have;
        try { return load(id).get(400, TimeUnit.MILLISECONDS); }
        catch (Exception slowOrGone) { return null; }
    }

    /** Asks LuckPerms for the identities of this account and keeps the answer */
    CompletableFuture<String> load(UUID id) {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms"));
        try {
            ClassLoader loader = luckPerms.getClass().getClassLoader();
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader).getMethod("get").invoke(null);
            Object users = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getUserManager").invoke(api);
            CompletableFuture<?> loaded = (CompletableFuture<?>) Class.forName("net.luckperms.api.model.user.UserManager", true, loader)
                    .getMethod("loadUser", UUID.class).invoke(users, id);
            return loaded.thenApply(user -> {
                try {
                    Object data = Class.forName("net.luckperms.api.model.user.User", true, loader).getMethod("getCachedData").invoke(user);
                    Object permissions = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, loader).getMethod("getPermissionData").invoke(data);
                    var check = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", true, loader).getMethod("checkPermission", String.class);
                    var asBoolean = Class.forName("net.luckperms.api.util.Tristate", true, loader).getMethod("asBoolean");
                    Predicate<String> has = node -> {
                        try { return Boolean.TRUE.equals(asBoolean.invoke(check.invoke(permissions, node))); }
                        catch (ReflectiveOperationException error) { return false; }
                    };
                    String now = DisplayLink.identities(has);
                    known.put(id, now);
                    remember(id, now);
                    return now;
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
        } catch (ReflectiveOperationException | LinkageError error) { return CompletableFuture.failedFuture(error); }
    }

    private void remember(UUID id, String now) {
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            try { plugin.profiles().find(id).filter(p -> !now.equals(p.preferences().get("identity"))).ifPresent(p -> plugin.profiles().put(p.withPreference("identity", now))); }
            catch (RuntimeException unavailable) { /* looked up again next time */ }
        });
    }

    /** In the background, once after start: every profile that has no remembered identity is looked up, a few at a time */
    void fillMissing() {
        Thread worker = new Thread(() -> {
            try {
                for (var profile : plugin.profiles().snapshot().values()) {
                    if (profile.preferences().get("identity") != null) continue;
                    try { load(profile.uuid()).get(5, TimeUnit.SECONDS); } catch (Exception skip) { /* later */ }
                    Thread.sleep(25);
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (RuntimeException unavailable) { /* profiles not ready */ }
        }, "tokaProfiles-identities");
        worker.setDaemon(true);
        worker.start();
    }
}
