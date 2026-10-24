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

    /** The identities an ordinary player can carry on top of being one, in the order their tags are shown. */
    private static final java.util.List<String> EXTRAS = java.util.List.of("sponsor", "linked");

    /**
     * Who a player is: there are two kinds, an administrator, or an ordinary player who may carry extra identities (sponsor, linked
     * Discord). The extras belong to ordinary players only, so an administrator is just "admin". Identities are permission nodes; no
     * group is looked at. An empty list is a plain ordinary player.
     */
    static java.util.List<String> identityIds(java.util.function.Predicate<String> has) {
        if (has.test("toka.identity.admin")) return java.util.List.of("admin");
        var found = new java.util.ArrayList<String>();
        for (String id : EXTRAS) if (has.test("toka.identity." + id)) found.add(id);
        return found;
    }

    /**
     * The tags in front of a name: those of the player's identities, or the ordinary tag when none of them has a tag to show. A tag
     * brings its own brackets and colour; a blank tag is not shown.
     */
    static String identity(java.util.function.Predicate<String> has, java.util.Map<String, String> tags) {
        var shown = new StringBuilder();
        for (String id : identityIds(has)) shown.append(tags.getOrDefault(id, ""));
        if (shown.length() == 0) shown.append(tags.getOrDefault("ordinary", ""));
        return shown.toString();
    }

    /** The identities as kept in the profile ("sponsor,linked"), so the tag can be composed for someone who is offline. */
    static String identities(java.util.function.Predicate<String> has) {
        return String.join(",", identityIds(has));
    }

    private static final long SETTLE_MILLIS = 30_000;

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
            final long since = System.currentTimeMillis();
            var tags = new java.util.HashMap<String, String>();
            var defaults = java.util.Map.of("admin", "§b[管]", "sponsor", "§e[特]", "linked", "", "ordinary", "§6[普]");
            for (String id : defaults.keySet()) tags.put(id, profiles.getConfig().getString("identity.tags." + id, defaults.get(id)));
            BiFunction<UUID, Player, String> identity = (id, online) -> {
                // Right after a reload the permissions of the players who are online are not attached yet: for a short while a player
                // who shows no identity at all is shown as the profile remembers them, like someone who is offline.
                boolean settling = System.currentTimeMillis() - since < SETTLE_MILLIS;
                if (online != null && !(settling && identityIds(online::hasPermission).isEmpty())) return identity(online::hasPermission, tags);
                try {
                    // someone offline (a message from Discord): what the profile remembers, else a look at LuckPerms; unknown shows no tag at all
                    String known = profiles.offline().lookup(id);
                    if (known == null) return "";
                    var kept = java.util.Set.of(known.split(","));
                    return identity(node -> kept.contains(node.substring("toka.identity.".length())), tags);
                } catch (RuntimeException unavailable) { return ""; }
            };
            api.getMethod("registerNameTagPart", String.class, String.class, BiFunction.class).invoke(null, profiles.getName(), "prefix", identity);
        } catch (ReflectiveOperationException | LinkageError older) {
            profiles.getLogger().warning("tokaDisplay 不支援名稱標籤片段（需要 0.4.0 以上）；稱號不會顯示在名稱標籤。");
        }
    }
}
