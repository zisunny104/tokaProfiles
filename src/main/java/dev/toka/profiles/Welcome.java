package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.i18n.Messages;
import dev.toka.profiles.storage.ProfileRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a new player must read before playing: pages shown one after the other, the last one with a button that starts the game. A
 * profile made at the first login carries {@code welcome=pending}; it becomes {@code done} when the last page is answered, and until
 * then the pages are shown again at every login. The pages are texts of the language file, so the server writes its own.
 */
final class Welcome implements Listener {
    static final String PREFERENCE = "welcome", PENDING = "pending", DONE = "done";
    private static final int PAGES = 1;

    private final Main plugin;
    private final Messages messages;
    private final Map<UUID, Integer> reading = new ConcurrentHashMap<>();

    Welcome(Main plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Shows the first page to a player whose profile still says pending. */
    void offer(Player player, ProfileRepository profiles) {
        boolean pending = profiles.find(player.getUniqueId()).map(p -> PENDING.equals(p.preferences().get(PREFERENCE))).orElse(false);
        if (pending && player.isOnline()) {
            player.setGamemode(2); // adventure until the pages are read
            registerHousehold(player);
            show(player, 1);
        }
    }

    /** A new player is registered in a village by tokaRegions, which knows the villages; without it, or without villages, nothing happens. */
    private void registerHousehold(Player player) {
        var regions = plugin.getServer().getPluginManager().getPlugin("tokaRegions");
        if (regions == null || !regions.isEnabled()) return;
        try { regions.getClass().getMethod("assignHousehold", UUID.class).invoke(null, player.getUniqueId()); }
        catch (ReflectiveOperationException | LinkageError unavailable) { /* the household can be set by an administrator */ }
    }

    private void show(Player player, int page) {
        reading.put(player.getUniqueId(), page);
        var form = new FormWindowSimple(messages.text("welcome.page" + page + ".title"), messages.text("welcome.page" + page + ".text", player.getName()));
        form.addButton(new ElementButton(messages.text(page == PAGES ? "welcome.start" : "welcome.next")));
        player.showFormWindow(form);
    }

    @EventHandler public void onForm(PlayerFormRespondedEvent event) {
        Player player = event.getPlayer();
        Integer page = reading.get(player.getUniqueId());
        if (page == null || !(event.getWindow() instanceof FormWindowSimple window)) return;
        if (!window.getTitle().equals(messages.text("welcome.page" + page + ".title"))) return;
        if (event.wasClosed() || event.getResponse() == null) {
            // The pages are required reading: a closed page comes back.
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> { if (player.isOnline() && reading.containsKey(player.getUniqueId())) show(player, page); }, 20);
            return;
        }
        if (page < PAGES) { show(player, page + 1); return; }
        reading.remove(player.getUniqueId());
        player.setGamemode(0);
        try {
            var repository = plugin.profiles();
            repository.find(player.getUniqueId()).ifPresent(p -> repository.put(p.withPreference(PREFERENCE, DONE)));
        } catch (RuntimeException unavailable) { /* shown again at the next login */ }
        plugin.getServer().broadcastMessage(messages.text("welcome.ready", player.getName()));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { reading.remove(event.getPlayer().getUniqueId()); }
}
