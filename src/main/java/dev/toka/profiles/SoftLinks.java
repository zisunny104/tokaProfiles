package dev.toka.profiles;

import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Looks at the other toka plugins by reflection, so none of them is needed and a missing or older one just shows nothing. Every method
 * answers empty (or null) when its source is not there; none throws.
 */
final class SoftLinks {
    record Balance(String name, String shown, String code, long amount) {}
    record Movement(long time, String currency, long amount) {}
    /** Claims of a player: how many, how many per type, the footprint area per type, and how many leases the player rents. */
    record Land(int count, Map<String, Integer> byType, Map<String, Long> areaByType, int rented) {
        long area() { return areaByType.values().stream().mapToLong(Long::longValue).sum(); }
    }

    private final cn.nukkit.Server server;

    SoftLinks(cn.nukkit.Server server) { this.server = server; }

    private Plugin running(String name) {
        Plugin plugin = server.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private Class<?> type(String plugin, String name) throws ReflectiveOperationException {
        Plugin found = running(plugin);
        if (found == null) throw new ClassNotFoundException(plugin);
        return Class.forName(name, true, found.getClass().getClassLoader());
    }

    // ---- tokaEconomy ----------------------------------------------------------------------------------------------

    List<Balance> balances(UUID id) {
        List<Balance> out = new ArrayList<>();
        try {
            Class<?> economy = type("tokaEconomy", "dev.toka.economy.Economy");
            String primary = (String) economy.getMethod("primary").invoke(null);
            List<String> codes = new ArrayList<>();
            for (Object code : (List<?>) economy.getMethod("currencies").invoke(null)) codes.add(String.valueOf(code));
            if (primary != null && codes.remove(primary)) codes.add(0, primary);
            var balance = economy.getMethod("balance", UUID.class, String.class);
            var format = economy.getMethod("format", long.class, String.class, boolean.class);
            var nameOf = economy.getMethod("currencyName", String.class, String.class);
            for (String code : codes) {
                Object result = balance.invoke(null, id, code);
                long amount = (long) result.getClass().getMethod("balance").invoke(result);
                out.add(new Balance((String) nameOf.invoke(null, code, "zh-TW"), (String) format.invoke(null, amount, code, true), code, amount));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return List.of(); }
        return out;
    }

    /** The code of the main currency, or null. */
    String primaryCurrency() {
        try { return (String) type("tokaEconomy", "dev.toka.economy.Economy").getMethod("primary").invoke(null); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return null; }
    }

    /** The newest ledger rows of the account, newest first. */
    List<Movement> movements(UUID id, int limit) {
        List<Movement> out = new ArrayList<>();
        try {
            for (Object row : (List<?>) type("tokaEconomy", "dev.toka.economy.Economy").getMethod("movements", UUID.class, int.class).invoke(null, id, limit)) {
                Class<?> c = row.getClass();
                out.add(new Movement((long) c.getMethod("time").invoke(row), (String) c.getMethod("currency").invoke(row), (long) c.getMethod("amount").invoke(row)));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return List.of(); }
        return out;
    }

    String formatMoney(long amount, String code) {
        try { return (String) type("tokaEconomy", "dev.toka.economy.Economy").getMethod("format", long.class, String.class, boolean.class).invoke(null, amount, code, true); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return String.format("%,d", amount); }
    }

    // ---- tokaRewards ----------------------------------------------------------------------------------------------

    /** The check-in record (lastDay, streak, total, automatic); completes empty when tokaRewards is not there. */
    CompletableFuture<Optional<JsonObject>> checkIn(UUID id) {
        try {
            Plugin rewards = running("tokaRewards");
            if (rewards == null) return CompletableFuture.completedFuture(Optional.empty());
            Object service = rewards.getClass().getMethod("service").invoke(rewards);
            CompletableFuture<?> profile = (CompletableFuture<?>) service.getClass().getMethod("profile", UUID.class).invoke(service, id);
            return profile.<Optional<JsonObject>>thenApply(json -> Optional.of(JsonParser.parseString(json.toString()).getAsJsonObject()))
                    .exceptionally(failure -> Optional.empty());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return CompletableFuture.completedFuture(Optional.empty()); }
    }

    // ---- tokaLand -------------------------------------------------------------------------------------------------

    Optional<Land> land(UUID id) {
        try {
            Class<?> main = type("tokaLand", "dev.toka.land.Main");
            Object land = main.getMethod("instance").invoke(null);
            if (land == null || !Boolean.TRUE.equals(land.getClass().getMethod("isReady").invoke(land))) return Optional.empty();
            Object index = land.getClass().getMethod("index").invoke(land);
            Map<String, Integer> count = new LinkedHashMap<>();
            Map<String, Long> area = new LinkedHashMap<>();
            int total = 0;
            for (Object claim : (List<?>) index.getClass().getMethod("ownedBy", UUID.class).invoke(index, id)) {
                Class<?> c = claim.getClass();
                String kind = String.valueOf(c.getMethod("kind").invoke(claim));
                String type = (String) c.getMethod("type").invoke(claim);
                String key = type == null || type.isBlank() ? kind : type;
                long footprint = 0;
                for (Object selection : (List<?>) c.getMethod("selections").invoke(claim)) {
                    Class<?> s = selection.getClass();
                    footprint += ((long) (int) s.getMethod("maxX").invoke(selection) - (int) s.getMethod("minX").invoke(selection) + 1)
                            * ((long) (int) s.getMethod("maxZ").invoke(selection) - (int) s.getMethod("minZ").invoke(selection) + 1);
                }
                count.merge(key, 1, Integer::sum);
                area.merge(key, footprint, Long::sum);
                total++;
            }
            int rented = 0;
            Object leases = land.getClass().getMethod("leases").invoke(land);
            if (leases != null) {
                for (Object lease : (List<?>) leases.getClass().getMethod("allLeases").invoke(leases)) {
                    Class<?> l = lease.getClass();
                    Object tenant = l.getField("tenant").get(lease), state = l.getField("state").get(lease);
                    if (id.toString().equals(tenant) && ("LEASED".equals(state) || "GRACE".equals(state))) rented++;
                }
            }
            return Optional.of(new Land(total, count, area, rented));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }

    // ---- tokaRegions ----------------------------------------------------------------------------------------------

    /** The household in tokaRegions, in its colour; null without it or without a household. */
    String household(UUID id) {
        try { return (String) type("tokaRegions", "dev.toka.regions.Main").getMethod("householdNameOf", UUID.class).invoke(null, id); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return null; }
    }
}
