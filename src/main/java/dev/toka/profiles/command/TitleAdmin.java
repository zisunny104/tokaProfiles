package dev.toka.profiles.command;

import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.ProfileRepository;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The title command for operators: {@code /profiles title <player or UUID> [title ... | clear]}.
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
            return "稱號最長 " + MAX_LENGTH + " 個字元";
        }
        for (int i = 0; i < title.length(); i++) {
            if (Character.isISOControl(title.charAt(i))) {
                return "稱號不能包含控制字元或換行";
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
            return CompletableFuture.completedFuture("用法：/profiles title <玩家名稱或 UUID> [稱號 | clear]");
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
                    profile.name() + " 的稱號：" + (title.isEmpty() ? "（沒有）" : title));
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
                    return "稱號未儲存：" + describe(failure);
                }
                return saved.isEmpty()
                        ? "已清除 " + profile.name() + " 的稱號"
                        : "已將 " + profile.name() + " 的稱號設為：" + saved;
            });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture("稱號未儲存：" + describe(e));
        }
    }

    private static Profile find(ProfileRepository profiles, String who) {
        UUID uuid = parseUuid(who);
        if (uuid != null) {
            return profiles.find(uuid).orElseThrow(() -> new IllegalArgumentException("找不到這個 UUID 的玩家資料：" + who));
        }

        Set<UUID> candidates = profiles.resolveName(who);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("找不到玩家「" + who + "」的資料");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("「" + who + "」對應到多筆玩家資料，請改用 UUID");
        }
        return profiles.find(candidates.iterator().next())
                .orElseThrow(() -> new IllegalArgumentException("找不到玩家「" + who + "」的資料"));
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
