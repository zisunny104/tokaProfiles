package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Gives tokaDisplay the player's title for the name tag, so Display needs no knowledge of profiles. Reflective, so
 * neither plugin links the other; it works for players who are offline too, because the title comes from the profile.
 */
final class DisplayLink {
    private DisplayLink() {}

    /** The title followed by the separator, or nothing when the player has no title. */
    static String text(String title, String suffix) {
        return title == null || title.isEmpty() ? "" : title + suffix;
    }

    /**
     * The identity tags in front of a name: one short tag for each identity the player has (administrator, sponsor, linked), in that
     * order, or the ordinary tag when they have none. A blank tag is not shown. Identities are permission nodes, so no group is looked at.
     */
    static String identity(java.util.function.Predicate<String> has, java.util.Map<String, String> tags) {
        var shown = new StringBuilder();
        for (String id : java.util.List.of("admin", "sponsor", "linked"))
            if (has.test("toka.identity." + id)) shown.append(tags.getOrDefault(id, ""));
        if (shown.length() == 0) shown.append(tags.getOrDefault("ordinary", ""));
        return shown.length() == 0 ? "" : "§8[" + shown + "§8]";
    }

    /** Registers the "title" part with tokaDisplay if it is running; safe to call again. */
    static void register(Main profiles, String suffix) {
        Plugin display = profiles.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            BiFunction<UUID, Player, String> title = (id, online) -> {
                try { return text(profiles.profiles().find(id).map(p -> p.title()).orElse(""), suffix); }
                catch (RuntimeException unavailable) { return ""; }
            };
            api.getMethod("registerNameTagPart", String.class, String.class, BiFunction.class).invoke(null, profiles.getName(), "title", title);
            var tags = new java.util.HashMap<String, String>();
            for (String id : java.util.List.of("admin", "sponsor", "linked", "ordinary")) tags.put(id, profiles.getConfig().getString("identity.tags." + id, ""));
            BiFunction<UUID, Player, String> identity = (id, online) -> online == null ? "" : identity(online::hasPermission, tags);
            api.getMethod("registerNameTagPart", String.class, String.class, BiFunction.class).invoke(null, profiles.getName(), "prefix", identity);
        } catch (ReflectiveOperationException | LinkageError older) {
            profiles.getLogger().warning("tokaDisplay 不支援名稱標籤片段（需要 0.4.0 以上）；稱號不會顯示在名稱標籤。");
        }
    }
}
