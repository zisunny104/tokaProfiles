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
        } catch (ReflectiveOperationException | LinkageError older) {
            profiles.getLogger().warning("tokaDisplay 不支援名稱標籤片段（需要 0.4.0 以上）；稱號不會顯示在名稱標籤。");
        }
    }
}
