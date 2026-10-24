package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.window.FormWindowCustom;
import dev.toka.i18n.Messages;
import dev.toka.profiles.legacy.LegacyRecord;
import dev.toka.profiles.model.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** The admin window of the city pass: notes of players and the old names that wait for a decision. Only for people with the permissions. */
final class AdminWindows {
    static final String NOTE = "tokaProfiles.note", LEGACY = "tokaProfiles.legacy";
    private static final int NOTE_LIMIT = 1000, LEGACY_SHOWN = 30;
    private static final String ICON_NOTE = "textures/items/book_writable", ICON_LEGACY = "textures/items/name_tag", ICON_PLAYER = "textures/items/spyglass",
            ICON_BACK = "textures/ui/arrow_left";

    private final Main plugin;
    private final Forms forms;
    private final Overview overview;

    AdminWindows(Main plugin, Forms forms, Overview overview) {
        this.plugin = plugin;
        this.forms = forms;
        this.overview = overview;
    }

    private Messages messages() { return plugin.messages(); }

    private String t(String key, Object... args) { return messages().text(key, args); }

    static boolean allowed(Player player) { return player.hasPermission(NOTE) || player.hasPermission(LEGACY); }

    void open(Player player) {
        if (!allowed(player)) { player.sendMessage(t("err.noNotePermission")); return; }
        List<Forms.Item> items = new ArrayList<>();
        items.add(Forms.Item.of(t("ui.back"), ICON_BACK, p -> plugin.cityPass().menu(p)));
        if (player.hasPermission(NOTE)) {
            items.add(Forms.Item.of(t("ui.admin.btnNotes"), ICON_NOTE, p -> pick(p, "", null, this::notes, this::open)));
            items.add(Forms.Item.of(t("ui.admin.btnOverview"), ICON_PLAYER, p -> pick(p, "", null, (q, id) -> overview.open(q, id, this::open), this::open)));
        }
        if (player.hasPermission(LEGACY)) items.add(Forms.Item.of(t("ui.admin.btnLegacy"), ICON_LEGACY, p -> legacySearch(p, "")));
        forms.menu(player, t("ui.admin.title"), "", items);
    }

    // ---- choosing a player -----------------------------------------------------------------------------------------

    /** Search narrows the online dropdown; a typed name (or UUID) of someone offline wins over the dropdown. */
    private void pick(Player player, String search, String error, BiConsumer<Player, UUID> next, Consumer<Player> back) {
        String needle = search.strip().toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        options.add(t("ui.admin.pick.none"));
        for (Player other : plugin.getServer().getOnlinePlayers().values()) {
            if (other.isOnline() && other.getName().toLowerCase(Locale.ROOT).contains(needle)) options.add(other.getName());
        }
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.pick.title"));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.pick.hint") : error));
        form.addElement(new ElementInput(t("ui.admin.pick.search"), t("ui.admin.pick.searchHint"), search));
        form.addElement(new ElementDropdown(t("ui.admin.pick.online"), options, options.size() == 2 ? 1 : 0));
        form.addElement(new ElementInput(t("ui.admin.pick.offline"), t("ui.admin.pick.offlineHint"), ""));
        forms.custom(player, form, response -> {
            String typedSearch = Forms.input(response, 1);
            String offline = Forms.input(response, 3);
            int index = response.getDropdownResponse(2) == null ? 0 : response.getDropdownResponse(2).getElementID();
            if (!offline.isEmpty()) {
                Optional<UUID> found = find(offline);
                if (found.isPresent()) next.accept(player, found.get());
                else pick(player, typedSearch, t("err.legacy.noPlayer"), next, back);
            } else if (index > 0 && index < options.size()) {
                Player chosen = plugin.getServer().getPlayerExact(options.get(index));
                if (chosen != null) next.accept(player, chosen.getUniqueId());
                else pick(player, typedSearch, t("err.legacy.noPlayer"), next, back);
            } else if (!typedSearch.isEmpty() && !typedSearch.equalsIgnoreCase(search.strip())) {
                pick(player, typedSearch, null, next, back);
            } else {
                pick(player, typedSearch, t("ui.admin.pick.missing"), next, back);
            }
        }, back);
    }

    /** A UUID, or a name that belongs to exactly one profile. */
    private Optional<UUID> find(String text) {
        try { return Optional.of(UUID.fromString(text)).filter(id -> plugin.profiles().find(id).isPresent()); }
        catch (IllegalArgumentException notUuid) { return plugin.profiles().findByName(text).map(Profile::uuid); }
        catch (IllegalStateException notReady) { return Optional.empty(); }
    }

    // ---- notes --------------------------------------------------------------------------------------------------------

    private void notes(Player player, UUID id) {
        notes(player, id, null);
    }

    private void notes(Player player, UUID id, String error) {
        Profile profile = plugin.profiles().find(id).orElse(null);
        if (profile == null) { player.sendMessage(t("err.legacy.noPlayer")); return; }
        String note = profile.preferences().getOrDefault("note", "");
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.notes.title", profile.name()));
        form.addElement(new ElementHeader(t("ui.admin.notes.current")));
        form.addElement(new ElementLabel(note.isBlank() ? t("ui.overview.none") : note));
        form.addElement(new ElementDivider());
        if (error != null) form.addElement(new ElementLabel(error)); else form.addElement(new ElementLabel(t("ui.admin.notes.hint", NOTE_LIMIT)));
        form.addElement(new ElementInput(t("ui.admin.notes.add"), t("ui.admin.notes.addHint"), ""));
        form.addElement(new ElementToggle(t("ui.admin.notes.clear"), false));
        forms.custom(player, form, response -> {
            String line = Forms.input(response, 4).replace("\n", " ");
            boolean clear = Boolean.TRUE.equals(response.getToggleResponse(5));
            if (clear) {
                forms.confirm(player, t("ui.admin.notes.clearTitle"), t("ui.admin.notes.clearAsk", profile.name()), t("ui.admin.accept"), t("ui.cancel"),
                        p -> saveNote(p, profile, ""), this::open);
            } else if (line.isEmpty()) {
                open(player);
            } else if (Notes.append(note, line).length() > NOTE_LIMIT) {
                notes(player, id, t("note.tooLong"));
            } else {
                saveNote(player, profile, Notes.append(note, line));
            }
        }, this::open);
    }

    private void saveNote(Player player, Profile profile, String text) {
        plugin.profiles().put(profile.withPreference("note", text)).whenComplete((done, failure) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            player.sendMessage(failure == null ? t(text.isEmpty() ? "note.cleared" : "note.saved", profile.name()) : t("err.title.notSaved", failure.getClass().getSimpleName()));
            if (player.isOnline()) notes(player, profile.uuid());
        }));
    }

    // ---- old names ------------------------------------------------------------------------------------------------------

    private void legacySearch(Player player, String query) {
        if (plugin.book() == null) { player.sendMessage(t("err.profilesNotReady")); return; }
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.legacy.title"));
        form.addElement(new ElementLabel(t("ui.admin.legacy.summary", plugin.book().size(), plugin.book().unclaimed(), plugin.book().waiting().size())));
        form.addElement(new ElementInput(t("ui.admin.legacy.query"), t("ui.admin.legacy.queryHint"), query));
        forms.custom(player, form, response -> legacyList(player, Forms.input(response, 1)), this::open);
    }

    private void legacyList(Player player, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<LegacyRecord> waiting = plugin.book().waiting().stream().filter(r -> r.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
        List<Forms.Item> items = new ArrayList<>();
        items.add(Forms.Item.of(t("ui.back"), ICON_BACK, p -> legacySearch(p, query)));
        for (LegacyRecord record : waiting.stream().limit(LEGACY_SHOWN).toList()) {
            items.add(Forms.Item.of(t("ui.admin.legacy.item", record.name(), record.disputed().size()), ICON_LEGACY, p -> legacyDetail(p, record.name(), query)));
        }
        String body = waiting.isEmpty() ? t("ui.admin.legacy.none") : waiting.size() > LEGACY_SHOWN ? t("ui.admin.legacy.more", waiting.size(), LEGACY_SHOWN) : "";
        forms.menu(player, t("ui.admin.legacy.list"), body, items);
    }

    private void legacyDetail(Player player, String name, String query) {
        Optional<LegacyRecord> found = plugin.book().find(name);
        if (found.isEmpty()) { legacyList(player, query); return; }
        LegacyRecord record = found.get();
        List<String> lines = new ArrayList<>();
        lines.add(t("ui.admin.legacy.name", record.name()));
        lines.add(t("ui.admin.legacy.village", blank(record.village())));
        lines.add(t("ui.admin.legacy.discord", blank(record.discordId())));
        lines.add(t("ui.admin.legacy.oldTitle", blank(record.title())));
        lines.add(t("ui.admin.legacy.owner", record.uuid() == null ? t("ui.overview.none") : who(record.uuid())));
        List<Forms.Item> items = new ArrayList<>();
        items.add(Forms.Item.of(t("ui.back"), ICON_BACK, p -> legacyList(p, query)));
        for (UUID candidate : record.disputed()) {
            items.add(Forms.Item.of(t("ui.admin.legacy.confirm", who(candidate)), ICON_PLAYER, p -> legacyAsk(p, record.name(), candidate, query)));
        }
        items.add(Forms.Item.of(t("ui.admin.legacy.other"), ICON_PLAYER, p -> pick(p, "", null, (q, id) -> legacyAsk(q, record.name(), id, query), q -> legacyDetail(q, name, query))));
        forms.menu(player, t("ui.admin.legacy.detail", record.name()), String.join("\n", lines), items);
    }

    private void legacyAsk(Player player, String name, UUID account, String query) {
        forms.confirm(player, t("ui.admin.legacy.askTitle"), t("ui.admin.legacy.ask", name, who(account), account), t("ui.admin.accept"), t("ui.cancel"),
                p -> plugin.inheritance().confirm(name, account).whenComplete((message, failure) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                    p.sendMessage(failure == null ? message : t("err.title.notSaved", failure.getClass().getSimpleName()));
                    if (p.isOnline()) legacyList(p, query);
                })), p -> legacyDetail(p, name, query));
    }

    private String who(UUID id) {
        try { return plugin.profiles().find(id).map(Profile::name).orElse(id.toString().substring(0, 8)); }
        catch (IllegalStateException notReady) { return id.toString().substring(0, 8); }
    }

    private String blank(String value) { return value == null || value.isBlank() ? t("ui.overview.none") : value; }
}
