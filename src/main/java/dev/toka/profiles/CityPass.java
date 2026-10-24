package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.InventoryClickEvent;
import cn.nukkit.event.inventory.InventoryMoveItemEvent;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.event.player.PlayerQuitEvent;
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
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The city pass: a compass every player carries, and the menu it opens. The menu only sends people on to the plugin that does the
 * thing (teleport, wallet, land); what it shows itself is short and plain: who you are, how the server is, how to get started.
 * Every button has an icon; the close button is red; pages that lead somewhere start with the way back.
 */
final class CityPass implements Listener {
    static final String ITEM_NAME = "§b⦋城市通⦌§r", OLD_ITEM_NAME = "§b[城市通]";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Main plugin;
    private final Messages messages;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    private final Forms forms;
    private final Overview overview;
    private final AdminWindows admin;

    CityPass(Main plugin, Messages messages, Forms forms, Overview overview, AdminWindows admin) {
        this.plugin = plugin;
        this.messages = messages;
        this.forms = forms;
        this.overview = overview;
        this.admin = admin;
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

    /** The game's clear command empties the inventory, pass included: give it back right after, to everyone it could have reached. */
    @EventHandler(priority = EventPriority.MONITOR) public void onClearByPlayer(cn.nukkit.event.player.PlayerCommandPreprocessEvent event) {
        if (!event.isCancelled() && clears(event.getMessage())) giveBackSoon();
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onClearByConsole(cn.nukkit.event.server.ServerCommandEvent event) {
        if (!event.isCancelled() && clears(event.getCommand())) giveBackSoon();
    }

    private static boolean clears(String command) {
        String text = command == null ? "" : command.strip().toLowerCase(java.util.Locale.ROOT);
        if (text.startsWith("/")) text = text.substring(1);
        if (text.startsWith("minecraft:")) text = text.substring("minecraft:".length());
        return text.equals("clear") || text.startsWith("clear ");
    }

    private void giveBackSoon() {
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> plugin.getServer().getOnlinePlayers().values().forEach(this::ensureItem), 2);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { lastUse.remove(event.getPlayer().getUniqueId()); }

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

    private static final String ICON_PORTAL = "textures/items/compass_item", ICON_WALLET = "textures/items/gold_ingot", ICON_SHOP = "textures/items/emerald",
            ICON_LAND = "textures/items/map_filled", ICON_PROFILE = "textures/items/name_tag", ICON_STATUS = "textures/items/clock_item",
            ICON_HELP = "textures/items/book_written", ICON_ADMIN = "textures/items/diamond", ICON_CLOSE = "textures/ui/cancel", ICON_BACK = "textures/ui/arrow_left",
            ICON_TITLE = "textures/items/name_tag";

    private Forms.Item command(String label, String icon, String command) {
        return Forms.Item.of(label, icon, p -> plugin.getServer().dispatchCommand(p, command));
    }

    private Forms.Item back(Consumer<Player> target) { return Forms.Item.of(text("citypass.back"), ICON_BACK, target); }

    void menu(Player player) {
        var items = new ArrayList<Forms.Item>(List.of(
                command(text("citypass.main.teleport"), ICON_PORTAL, "portal"), command(text("citypass.main.wallet"), ICON_WALLET, "balance"),
                command(text("citypass.main.shop"), ICON_SHOP, "shop"), command(text("citypass.main.land"), ICON_LAND, "land menu"),
                Forms.Item.of(text("citypass.main.profile"), ICON_PROFILE, p -> overview.open(p, p.getUniqueId(), this::menu)),
                Forms.Item.of(text("citypass.main.status"), ICON_STATUS, this::status), Forms.Item.of(text("citypass.main.help"), ICON_HELP, this::help)));
        if (AdminWindows.allowed(player)) items.add(Forms.Item.of(text("citypass.main.admin"), ICON_ADMIN, admin::open));
        items.add(Forms.Item.of(text("citypass.close"), ICON_CLOSE, p -> { }));
        forms.menu(player, text("citypass.main.title"), "", items);
    }

    private void status(Player player) {
        var server = plugin.getServer();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) >> 20, max = runtime.maxMemory() >> 20;
        String body = text("citypass.status.body", server.getOnlinePlayers().size(), server.getMaxPlayers(), (int) server.getTicksPerSecond(), used + "/" + max, LocalDateTime.now().format(TIME));
        forms.menu(player, text("citypass.status.title"), body, List.of(back(this::menu)));
    }

    private static final List<String> SECTIONS = List.of("teleport", "land", "wallet", "shop", "profile", "rules");

    private void help(Player player) {
        var items = new ArrayList<Forms.Item>();
        items.add(back(this::menu));
        for (String section : SECTIONS) items.add(Forms.Item.of(text("citypass.help." + section + ".label"), ICON_HELP, p -> helpSection(p, section)));
        forms.menu(player, text("citypass.help.title"), "", items);
    }

    private void helpSection(Player player, String section) {
        forms.menu(player, text("citypass.help." + section + ".title"), text("citypass.help." + section + ".body"), List.of(back(this::help)));
    }

    /** The title form: the current title, the limit, the new title and a switch that clears it. Both ways go through /profiles mytitle. */
    void editTitle(Player player) {
        int limit = plugin.getConfig().getInt("title.self.max-length", 16);
        String current = plugin.profiles().find(player.getUniqueId()).map(p -> p.title()).orElse("");
        var form = new FormWindowCustom(text("citypass.titleEdit.title"));
        form.addElement(new ElementHeader(text("citypass.titleEdit.current")));
        form.addElement(new ElementLabel(current.isEmpty() ? text("citypass.none") : current));
        form.addElement(new ElementDivider());
        form.addElement(new ElementLabel(text(player.hasPermission(dev.toka.profiles.command.SelfTitle.COLOR_PERMISSION) ? "citypass.titleEdit.limitColour" : "citypass.titleEdit.limit", limit)));
        form.addElement(new ElementInput(text("citypass.titleEdit.label"), text("citypass.titleEdit.placeholder", limit), current));
        form.addElement(new ElementToggle(text("citypass.titleEdit.clear"), false));
        forms.custom(player, form, response -> {
            String typed = Forms.input(response, 4);
            if (Boolean.TRUE.equals(response.getToggleResponse(5))) plugin.getServer().dispatchCommand(player, "profiles mytitle clear");
            else if (!typed.isEmpty() && !typed.equals(current)) plugin.getServer().dispatchCommand(player, "profiles mytitle " + typed);
        }, p -> { });
    }
}
