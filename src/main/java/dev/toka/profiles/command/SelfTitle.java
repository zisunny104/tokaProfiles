package dev.toka.profiles.command;

import dev.toka.profiles.Texts;
import dev.toka.profiles.storage.ProfileRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player changing their own title: {@code /profiles mytitle [title | clear]}. Who may do it is a permission, so identity
 * groups can be given the right later; the rest is limited here: length, no hidden characters, colours only with their own
 * permission, a list of blocked words, and a wait between changes.
 */
public final class SelfTitle {
    public static final String PERMISSION = "tokaProfiles.title.self";
    public static final String COLOR_PERMISSION = "tokaProfiles.title.color";

    public record Rules(int maxLength, long cooldownMillis, List<String> blockedWords) {
        public Rules {
            if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
            blockedWords = blockedWords.stream().map(w -> w.toLowerCase(Locale.ROOT)).filter(w -> !w.isBlank()).toList();
        }
    }

    /** A reason a title is refused: a language key and its argument. */
    public record Problem(String key, Object argument) {}

    private final Map<UUID, Long> lastChange = new ConcurrentHashMap<>();

    /** The title with colours as the game writes them (& becomes the section sign), or null with the reason in {@code out}. */
    public static Problem check(String title, boolean mayColour, Rules rules) {
        for (int i = 0; i < title.length(); i++) if (Character.isISOControl(title.charAt(i))) return new Problem("err.title.control", null);
        boolean colour = title.indexOf('§') >= 0 || title.indexOf('&') >= 0;
        if (colour && !mayColour) return new Problem("err.title.selfColour", null);
        String visible = stripColour(title);
        if (visible.isBlank()) return new Problem("err.title.selfBlank", null);
        if (visible.length() > rules.maxLength()) return new Problem("err.title.selfLong", rules.maxLength());
        String plain = visible.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String word : rules.blockedWords()) if (plain.contains(word.replace(" ", ""))) return new Problem("err.title.selfBlocked", null);
        return null;
    }

    /** Text without colour codes, for measuring and for the blocked-word check. */
    static String stripColour(String text) {
        return text.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
    }

    /** Seconds the player still has to wait, or 0. */
    public long waitSeconds(UUID player, long now, long cooldownMillis) {
        Long last = lastChange.get(player);
        if (last == null) return 0;
        long left = last + cooldownMillis - now;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    public void changed(UUID player, long now) { lastChange.put(player, now); }

    /**
     * @param rest what follows {@code mytitle}: nothing to show the title, "clear" to remove it, or the new title
     */
    public CompletableFuture<String> run(ProfileRepository profiles, UUID player, boolean mayColour, boolean bypassWait, String[] rest, Rules rules, long now) {
        var profile = profiles.find(player);
        if (profile.isEmpty()) return CompletableFuture.completedFuture(Texts.get().text("err.title.selfNoProfile"));
        if (rest.length == 0) {
            String current = profile.get().title();
            return CompletableFuture.completedFuture(Texts.get().text("msg.title.mine", current.isEmpty() ? Texts.get().text("status.noTitle") : current));
        }
        String title = String.join(" ", rest).trim();
        boolean clear = title.equalsIgnoreCase(TitleAdmin.CLEAR);
        if (!clear) {
            Problem problem = check(title, mayColour, rules);
            if (problem != null) return CompletableFuture.completedFuture(Texts.get().text(problem.key(), problem.argument() == null ? new Object[0] : new Object[]{problem.argument()}));
        }
        long wait = bypassWait ? 0 : waitSeconds(player, now, rules.cooldownMillis());
        if (wait > 0) return CompletableFuture.completedFuture(Texts.get().text("err.title.selfWait", wait));
        String saved = clear ? "" : title.replace('&', '§');
        return profiles.updateTitle(player, saved).handle((ignored, failure) -> {
            if (failure != null) return Texts.get().text("err.title.notSaved", failure.getMessage());
            changed(player, now);
            return saved.isEmpty() ? Texts.get().text("msg.title.mineCleared") : Texts.get().text("msg.title.mineSet", saved);
        });
    }
}
