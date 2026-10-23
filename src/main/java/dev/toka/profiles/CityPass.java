package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.InventoryClickEvent;
import cn.nukkit.event.inventory.InventoryMoveItemEvent;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.inventory.transaction.action.DropItemAction;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemID;
import dev.toka.i18n.Messages;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The city pass: a compass every player carries, and the menu it opens. The menu only sends people on to the plugin that does the
 * thing (teleport, wallet, land); what it shows itself is short and plain: who you are, how the server is, how to get started.
 * Buttons are plain words, no symbols or colours, and every page has the same way back.
 */
final class CityPass implements Listener {
    static final String ITEM_NAME = "§b⦋城市通⦌§r", OLD_ITEM_NAME = "§b[城市通]";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Main plugin;
    private final Messages messages;
    /** What each button of the page a player has open does, in order. */
    private final Map<UUID, Screen> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    private record Screen(String title, List<String> actions) {}

    CityPass(Main plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    private String text(String key, Object... args) { return messages.text(key, args); }

    // ---- the item ----

    static boolean isPass(Item item) {
        return item != null && item.getId() == ItemID.COMPASS && (ITEM_NAME.equals(item.getCustomName()) || OLD_ITEM_NAME.equals(item.getCustomName()));
    }

    /** Gives the pass when the player has none, in the old hotbar slot if it is free, else in the first free one. */
    void ensureItem(Player player) {
        for (Item existing : player.getInventory().getContents().values()) if (isPass(existing)) return;
        for (Item existing : player.getInventory().getArmorContents()) if (isPass(existing)) return;
        for (Item existing : player.getOffhandInventory().getContents().values()) if (isPass(existing)) return;
        Item pass = Item.get(ItemID.COMPASS);
        pass.setCustomName(ITEM_NAME);
        var inventory = player.getInventory();
        if (inventory.getSize() > 8 && inventory.getItem(8).isNull()) { inventory.setItem(8, pass); return; }
        for (int slot = 0; slot < inventory.getSize(); slot++) if (inventory.getItem(slot).isNull()) { inventory.setItem(slot, pass); return; }
        player.sendMessage(text("citypass.inventoryFull"));
    }

    @EventHandler(priority = EventPriority.HIGH) public void onJoin(PlayerJoinEvent event) { ensureItem(event.getPlayer()); }

    @EventHandler public void onQuit(PlayerQuitEvent event) { open.remove(event.getPlayer().getUniqueId()); lastUse.remove(event.getPlayer().getUniqueId()); }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (!isPass(event.getItem())) return;
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long before = lastUse.put(player.getUniqueId(), now);
        if (before == null || now - before >= 260) menu(player); // the client sends one touch twice
    }

    @EventHandler(priority = EventPriority.HIGH) public void onDrop(PlayerDropItemEvent event) { if (isPass(event.getItem())) event.setCancelled(); }

    @EventHandler(priority = EventPriority.HIGH) public void onMove(InventoryMoveItemEvent event) { if (isPass(event.getItem())) event.setCancelled(); }

    @EventHandler public void onClick(InventoryClickEvent event) { if (isPass(event.getSourceItem())) event.setCancelled(); }

    @EventHandler public void onTransaction(InventoryTransactionEvent event) {
        for (var action : event.getTransaction().getActions())
            if (action instanceof DropItemAction drop && isPass(drop.getTargetItem())) event.setCancelled();
    }

    // ---- the pages ----

    private void show(Player player, String key, String body, List<String> labels, List<String> actions) {
        String title = text("citypass." + key + ".title");
        var form = new FormWindowSimple(title, body);
        labels.forEach(label -> form.addButton(new ElementButton(label)));
        open.put(player.getUniqueId(), new Screen(title, actions));
        player.showFormWindow(form);
    }

    void menu(Player player) {
        var labels = new ArrayList<>(List.of(text("citypass.main.teleport"), text("citypass.main.wallet"), text("citypass.main.land"),
                text("citypass.main.profile"), text("citypass.main.status"), text("citypass.main.help")));
        var actions = new ArrayList<>(List.of("command:portal", "command:balance", "command:land menu", "profile", "status", "help"));
        labels.add(text("citypass.close"));
        actions.add("close");
        show(player, "main", text("citypass.main.body"), labels, actions);
    }

    private void profile(Player player) {
        var found = plugin.profiles().find(player.getUniqueId());
        String title = found.map(p -> p.title().isEmpty() ? text("citypass.none") : p.title()).orElse(text("citypass.none"));
        String household = householdName(player);
        String body = text("citypass.profile.body", player.getName(), title, household == null ? text("citypass.none") : household, identity(player));
        var labels = new ArrayList<String>();
        var actions = new ArrayList<String>();
        if (player.hasPermission("tokaProfiles.title.self")) { labels.add(text("citypass.profile.editTitle")); actions.add("editTitle"); }
        labels.add(text("citypass.back")); actions.add("menu");
        show(player, "profile", body, labels, actions);
    }

    /** The household in tokaRegions, in its colour; null without it or without a household. */
    private String householdName(Player player) {
        var regions = plugin.getServer().getPluginManager().getPlugin("tokaRegions");
        if (regions == null || !regions.isEnabled()) return null;
        try { return (String) regions.getClass().getMethod("householdNameOf", UUID.class).invoke(null, player.getUniqueId()); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    private String identity(Player player) {
        var ids = DisplayLink.identityIds(player::hasPermission);
        return ids.isEmpty() ? text("citypass.identity.ordinary") : String.join("、", ids.stream().map(id -> text("citypass.identity." + id)).toList());
    }

    private void status(Player player) {
        var server = plugin.getServer();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) >> 20, max = runtime.maxMemory() >> 20;
        String body = text("citypass.status.body", server.getOnlinePlayers().size(), server.getMaxPlayers(), (int) server.getTicksPerSecond(), used + "/" + max, LocalDateTime.now().format(TIME));
        show(player, "status", body, List.of(text("citypass.back")), List.of("menu"));
    }

    private void help(Player player) {
        show(player, "help", String.join("\n", messages.lines("citypass.help.lines")), List.of(text("citypass.back")), List.of("menu"));
    }

    private void editTitle(Player player) {
        String title = text("citypass.titleEdit.title");
        var form = new FormWindowCustom(title);
        form.addElement(new ElementInput(text("citypass.titleEdit.label"), text("citypass.titleEdit.placeholder")));
        open.put(player.getUniqueId(), new Screen(title, List.of("titleSubmit")));
        player.showFormWindow(form);
    }

    @EventHandler public void onForm(PlayerFormRespondedEvent event) {
        Player player = event.getPlayer();
        Screen screen = open.get(player.getUniqueId());
        FormWindow window = event.getWindow();
        String shown = window instanceof FormWindowSimple simple ? simple.getTitle() : window instanceof FormWindowCustom custom ? custom.getTitle() : null;
        if (screen == null || shown == null || !shown.equals(screen.title())) return;
        open.remove(player.getUniqueId());
        if (event.wasClosed() || event.getResponse() == null) return;
        String action;
        if (event.getResponse() instanceof FormResponseSimple simple) {
            int id = simple.getClickedButtonId();
            if (id < 0 || id >= screen.actions().size()) return;
            action = screen.actions().get(id);
        } else if (event.getResponse() instanceof FormResponseCustom custom) {
            String typed = custom.getInputResponse(0) == null ? "" : custom.getInputResponse(0).strip();
            plugin.getServer().dispatchCommand(player, "profiles mytitle " + (typed.isEmpty() ? "clear" : typed));
            return;
        } else return;
        if (action.startsWith("command:")) { plugin.getServer().dispatchCommand(player, action.substring("command:".length())); return; }
        switch (action) {
            case "menu" -> menu(player);
            case "profile" -> profile(player);
            case "status" -> status(player);
            case "help" -> help(player);
            case "editTitle" -> editTitle(player);
            default -> { /* close */ }
        }
    }
}
