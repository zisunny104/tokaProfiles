package dev.toka.profiles.command;

import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.ProfileRepository;

import dev.toka.profiles.Texts;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The title command for operators: {@code /profile title <player or UUID> [title ... | clear]}.
 * <p>
 * It only reads and writes the title of a profile through {@link ProfileRepository}. Buying a title and the
 * payment for it stay out of this plugin until a transaction across the two stores is verified.
 */
public final class TitleAdmin {

    public static final String PERMISSION = "tokaProfiles.title.admin";
    public static final String CLEAR = "clear";
    public static final int MAX_LENGTH = 255;

    private TitleAdmin() {
    }

    /**
     * @return null when the title can be saved, otherwise the reason it cannot
     */
    public static String validate(String title) {
        if (title.length() > MAX_LENGTH) {
            return Texts.get().text("err.title.tooLong", MAX_LENGTH);
        }
        for (int i = 0; i < title.length(); i++) {
            if (Character.isISOControl(title.charAt(i))) {
                return Texts.get().text("err.title.control");
            }
        }
        return null;
    }

    /**
     * @param args what follows {@code title}: the player, then the new title, or {@code clear}, or nothing to show it
     * @return the message to send to the sender, once the title is saved
     */
    public static CompletableFuture<String> run(ProfileRepository profiles, String[] args) {
        if (args.length == 0) {
            return CompletableFuture.completedFuture(Texts.get().text("err.usage", Texts.get().text("cmd.profiles.usageTitle")));
        }

        Profile profile;
        try {
            profile = find(profiles, args[0]);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(e.getMessage());
        }

        if (args.length == 1) {
            String title = profile.title();
            return CompletableFuture.completedFuture(
                    Texts.get().text("msg.title.show", profile.name(), title.isEmpty() ? Texts.get().text("status.noTitle") : title));
        }

        String title = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        boolean clear = title.equalsIgnoreCase(CLEAR);
        if (clear) {
            title = "";
        } else {
            String problem = validate(title);
            if (problem != null) {
                return CompletableFuture.completedFuture(problem);
            }
        }

        String saved = title;
        try {
            return profiles.updateTitle(profile.uuid(), saved).handle((ignored, failure) -> {
                if (failure != null) {
                    return Texts.get().text("err.title.notSaved", describe(failure));
                }
                return saved.isEmpty()
                        ? Texts.get().text("msg.title.cleared", profile.name())
                        : Texts.get().text("msg.title.set", profile.name(), saved);
            });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Texts.get().text("err.title.notSaved", describe(e)));
        }
    }

    private static Profile find(ProfileRepository profiles, String who) {
        UUID uuid = parseUuid(who);
        if (uuid != null) {
            return profiles.find(uuid).orElseThrow(() -> new IllegalArgumentException(Texts.get().text("err.title.noUuid", who)));
        }

        Set<UUID> candidates = profiles.resolveName(who);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(Texts.get().text("err.title.noPlayer", who));
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException(Texts.get().text("err.title.ambiguous", who));
        }
        return profiles.find(candidates.iterator().next())
                .orElseThrow(() -> new IllegalArgumentException(Texts.get().text("err.title.noPlayer", who)));
    }

    private static UUID parseUuid(String value) {
        if (value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }
}
