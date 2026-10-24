package dev.toka.profiles;

import cn.nukkit.Player;
import com.google.gson.JsonObject;
import dev.toka.i18n.Messages;
import dev.toka.profiles.model.Profile;
import dev.toka.profiles.view.Chart;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The personal overview window (who you are, what you have, what you did) and the statistics window behind it. The data of other
 * plugins comes through {@link SoftLinks}, so a plugin that is missing just leaves its section out. An administrator can open the same
 * window for another player and then also sees the note and the old names of that player.
 */
final class Overview {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String ICON_TITLE = "textures/items/name_tag", ICON_STATS = "textures/items/book_writable", ICON_WALLET = "textures/items/gold_ingot",
            ICON_LAND = "textures/items/map_filled", ICON_CHECKIN = "textures/items/clock_item", ICON_BACK = "textures/ui/arrow_left";

    private final Main plugin;
    private final Forms forms;
    private final SoftLinks links;

    Overview(Main plugin, Forms forms, SoftLinks links) {
        this.plugin = plugin;
        this.forms = forms;
        this.links = links;
    }

    private Messages messages() { return plugin.messages(); }

    private String t(String key, Object... args) { return messages().text(key, args); }

    private String section(String key) { return "■ " + t("ui.overview.sec." + key); }

    // ---- the overview --------------------------------------------------------------------------------------------

    /** @param back what the back button does; null for a plain close */
    void open(Player viewer, UUID target, Consumer<Player> back) {
        Optional<Profile> found;
        try { found = plugin.profiles().find(target); }
        catch (IllegalStateException notReady) { viewer.sendMessage(t("err.profilesNotReady")); return; }
        if (found.isEmpty()) { viewer.sendMessage(t("err.legacy.noPlayer")); return; }
        Profile profile = found.get();
        links.checkIn(target).thenAccept(checkIn -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (viewer.isOnline()) show(viewer, profile, checkIn, back);
        }));
    }

    private void show(Player viewer, Profile profile, Optional<JsonObject> checkIn, Consumer<Player> back) {
        UUID id = profile.uuid();
        boolean self = viewer.getUniqueId().equals(id);
        List<String> lines = new ArrayList<>();
        lines.add(section("base"));
        lines.add(t("ui.overview.name", profile.name()));
        lines.add(t("ui.overview.id", id));
        if (!profile.aliases().isEmpty()) lines.add(t("ui.overview.aliases", String.join("、", profile.aliases())));
        lines.add(t("ui.overview.title", profile.title().isEmpty() ? t("ui.overview.none") : profile.title()));
        List<String> identities = identities(id);
        lines.add(t("ui.overview.identity", identities.isEmpty() ? t("citypass.identity.ordinary") : String.join("、", identities.stream().map(i -> t("citypass.identity." + i)).toList())));
        String household = links.household(id);
        lines.add(t("ui.overview.household", household == null ? t("ui.overview.none") : household));
        lines.add(t("ui.overview.chat", DisplayLink.nameTag(identities, DisplayLink.tags(plugin), profile.title(), plugin.getConfig().getString("nametag.title-suffix", "§6-"), profile.name())));
        Player online = plugin.getServer().getOnlinePlayers().get(id);
        var offlinePlayer = plugin.getServer().getOfflinePlayer(id);
        Long first = online != null ? online.getFirstPlayed() : offlinePlayer == null ? null : offlinePlayer.getFirstPlayed();
        Long last = offlinePlayer == null ? null : offlinePlayer.getLastPlayed();
        if (first != null && first > 0) lines.add(t("ui.overview.first", time(first)));
        lines.add(t("ui.overview.last", online != null ? t("ui.overview.online") : last != null && last > 0 ? time(last) : t("ui.overview.none")));
        var balances = links.balances(id);
        if (!balances.isEmpty()) {
            lines.add(section("wallet"));
            balances.forEach(b -> lines.add(t("ui.overview.money", b.name(), b.shown())));
        }
        checkIn.ifPresent(json -> {
            lines.add(section("checkin"));
            int streak = Chart.liveStreak(LocalDate.now().toEpochDay(), day(json).map(LocalDate::toEpochDay).orElse(Long.MIN_VALUE / 2), json.get("streak").getAsInt());
            lines.add(t("ui.overview.streak", streak, Chart.commas(json.get("total").getAsLong())));
            lines.add(t("ui.overview.lastDay", day(json).map(LocalDate::toString).orElse(t("ui.overview.none"))));
        });
        links.land(id).ifPresent(land -> {
            lines.add(section("land"));
            lines.add(t("ui.overview.land", land.count(), land.rented()));
        });
        lines.add(section("discord"));
        if (identities.contains("linked")) lines.add(t("ui.overview.discord.linked"));
        else {
            lines.add(t("ui.overview.discord.none"));
            if (self) lines.add(t("ui.overview.discord.hint"));
        }
        if (!self) {
            lines.add(section("notes"));
            String note = profile.preferences().getOrDefault("note", "");
            lines.add(note.isBlank() ? t("ui.overview.none") : note);
            var legacy = plugin.legacyOf(id);
            if (!legacy.isEmpty()) {
                lines.add(section("legacy"));
                legacy.forEach(entry -> lines.add(t("ui.overview.legacy", entry.get("name"), blank(entry.get("village")), blank(entry.get("discordId")), blank(entry.get("title")))));
            }
        }
        List<Forms.Item> items = new ArrayList<>();
        if (self && viewer.hasPermission("tokaProfiles.title.self")) items.add(Forms.Item.of(t("ui.overview.btn.title"), ICON_TITLE, p -> plugin.cityPass().editTitle(p)));
        items.add(Forms.Item.of(t("ui.overview.btn.stats"), ICON_STATS, p -> stats(p, profile, 7, back)));
        if (self) {
            items.add(Forms.Item.of(t("ui.overview.btn.wallet"), ICON_WALLET, p -> plugin.getServer().dispatchCommand(p, "balance")));
            items.add(Forms.Item.of(t("ui.overview.btn.land"), ICON_LAND, p -> plugin.getServer().dispatchCommand(p, "land menu")));
            items.add(Forms.Item.of(t("ui.overview.btn.checkin"), ICON_CHECKIN, p -> plugin.getServer().dispatchCommand(p, "reward status")));
        }
        items.add(Forms.Item.of(t("ui.back"), ICON_BACK, back == null ? p -> { } : back));
        forms.menu(viewer, self ? t("ui.overview.heading") : t("ui.overview.headingOf", profile.name()), String.join("\n", lines), items);
    }

    private String blank(String value) { return value == null || value.isBlank() ? t("ui.overview.none") : value; }

    private static String time(long millis) { return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())); }

    private static Optional<LocalDate> day(JsonObject checkIn) {
        try { return Optional.of(LocalDate.parse(checkIn.get("lastDay").getAsString())); }
        catch (RuntimeException none) { return Optional.empty(); }
    }

    /** The identity ids of an account: from the permissions of an online player, else what the profile remembers. */
    private List<String> identities(UUID id) {
        Player online = plugin.getServer().getOnlinePlayers().get(id);
        if (online != null) return DisplayLink.identityIds(online::hasPermission);
        String kept = plugin.offline().remembered(id);
        return kept == null || kept.isBlank() ? List.of() : List.of(kept.split(","));
    }

    // ---- statistics ----------------------------------------------------------------------------------------------

    private void stats(Player viewer, Profile profile, int days, Consumer<Player> back) {
        UUID id = profile.uuid();
        links.checkIn(id).thenAccept(checkIn -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (viewer.isOnline()) showStats(viewer, profile, checkIn, days, back);
        }));
    }

    private void showStats(Player viewer, Profile profile, Optional<JsonObject> checkIn, int days, Consumer<Player> back) {
        UUID id = profile.uuid();
        List<String> lines = new ArrayList<>();
        checkIn.ifPresent(json -> {
            long today = LocalDate.now().toEpochDay();
            long last = day(json).map(LocalDate::toEpochDay).orElse(Long.MIN_VALUE / 2);
            int streak = Chart.liveStreak(today, last, json.get("streak").getAsInt());
            lines.add(section("checkin"));
            lines.add(t("ui.stats.streak", streak, 30) + "  " + Chart.bar(streak, 30));
            lines.add(t("ui.stats.week") + "  " + Chart.strip(Chart.checkInDays(today, last, json.get("streak").getAsInt(), 7)));
        });
        String primary = links.primaryCurrency();
        var movements = primary == null ? List.<SoftLinks.Movement>of() : links.movements(id, 500).stream().filter(m -> m.currency().equals(primary)).toList();
        if (!movements.isEmpty()) {
            long since = System.currentTimeMillis() - days * 86_400_000L;
            long income = 0, expense = 0;
            for (var m : movements) {
                if (m.time() < since) continue;
                if (m.amount() > 0) income += m.amount(); else expense -= m.amount();
            }
            long max = Math.max(income, expense);
            lines.add(section("flow"));
            lines.add(t("ui.stats.flowDays", days));
            lines.add(t("ui.stats.income") + "  " + Chart.bar(income, max) + "  " + Chart.commas(income));
            lines.add(t("ui.stats.expense") + "  " + Chart.bar(expense, max) + "  " + Chart.commas(expense));
            lines.add(t("ui.stats.net", Chart.change(income - expense)));
            long current = links.balances(id).stream().filter(b -> b.code().equals(primary)).mapToLong(SoftLinks.Balance::amount).findFirst().orElse(0);
            List<Long> trend = Chart.balancesAfter(current, movements.stream().map(SoftLinks.Movement::amount).toList(), 5);
            if (trend.size() >= 2) {
                long top = trend.stream().mapToLong(Long::longValue).max().orElse(0);
                lines.add(section("trend"));
                lines.add(t("ui.stats.trendPoints", trend.size()));
                trend.forEach(value -> lines.add(Chart.bar(value, top) + "  " + links.formatMoney(value, primary)));
            }
        }
        links.land(id).filter(land -> land.count() > 0).ifPresent(land -> {
            long top = land.areaByType().values().stream().mapToLong(Long::longValue).max().orElse(0);
            lines.add(section("land"));
            land.byType().forEach((type, count) -> lines.add(t("ui.stats.landType", type, count) + "  " + Chart.bar(land.areaByType().getOrDefault(type, 0L), top) + "  " + Chart.commas(land.areaByType().getOrDefault(type, 0L))));
            lines.add(t("ui.stats.landTotal", Chart.commas(land.area())));
        });
        if (lines.isEmpty()) lines.add(t("ui.stats.empty"));
        List<Forms.Item> items = new ArrayList<>();
        items.add(Forms.Item.of(t("ui.back"), ICON_BACK, p -> open(p, id, back)));
        int other = days == 7 ? 30 : 7;
        items.add(Forms.Item.of(t("ui.stats.range", other), null, p -> stats(p, profile, other, back)));
        forms.menu(viewer, t("ui.stats.heading", profile.name()), String.join("\n", lines), items);
    }
}
