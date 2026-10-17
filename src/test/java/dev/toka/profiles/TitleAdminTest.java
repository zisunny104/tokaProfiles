package dev.toka.profiles;

import dev.toka.profiles.command.TitleAdmin;
import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.ProfileRepository;
import dev.toka.storage.Backend;
import dev.toka.storage.StoreConfig;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TitleAdminTest {

    private static ProfileRepository open(Profile... profiles) throws Exception {
        Path root = Path.of(".build-tools/tests");
        Files.createDirectories(root);
        Path path = Files.createTempDirectory(root, "title-admin-").resolve("data");
        ProfileRepository repository = ProfileRepository.open(new StoreConfig(Backend.SQLITE, path, 1), 16);
        for (Profile profile : profiles) {
            repository.put(profile).get(10, TimeUnit.SECONDS);
        }
        return repository;
    }

    private static String text(String key, Object... arguments) {
        return dev.toka.profiles.Texts.get().text(key, arguments);
    }

    private static Profile profile(UUID uuid, String name, String... aliases) {
        return new Profile(uuid, name, Set.of(aliases), Map.of(), null);
    }

    private static String run(ProfileRepository repository, String... args) throws Exception {
        return TitleAdmin.run(repository, args).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void validatesTheTitle() {
        assertNull(TitleAdmin.validate("村長"));
        assertNull(TitleAdmin.validate("§6金色稱號"));
        assertNotNull(TitleAdmin.validate("a\nb"));
        assertNotNull(TitleAdmin.validate("x".repeat(TitleAdmin.MAX_LENGTH + 1)));
        assertNull(TitleAdmin.validate("x".repeat(TitleAdmin.MAX_LENGTH)));
    }

    @Test
    public void setsShowsAndClearsATitle() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (ProfileRepository repository = open(profile(uuid, "Sunny"))) {
            assertEquals(text("msg.title.show", "Sunny", text("status.noTitle")), run(repository, "sunny"));

            assertEquals(text("msg.title.set", "Sunny", "村長 一號"), run(repository, "Sunny", "村長", "一號"));
            assertEquals("村長 一號", repository.find(uuid).orElseThrow().title());
            assertEquals(text("msg.title.show", "Sunny", "村長 一號"), run(repository, "Sunny"));

            assertEquals(text("msg.title.cleared", "Sunny"), run(repository, "Sunny", "clear"));
            assertEquals("", repository.find(uuid).orElseThrow().title());
        }
    }

    @Test
    public void findsByUuidAndRejectsUnknownOrAmbiguousPlayers() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (ProfileRepository repository = open(profile(a, "Alice", "Shared"), profile(b, "Bob", "Shared"))) {
            assertTrue(run(repository, a.toString(), "王").contains("Alice"));
            assertEquals("王", repository.find(a).orElseThrow().title());

            assertTrue(run(repository, "Nobody", "x").contains("找不到玩家"));
            assertTrue(run(repository, "Shared", "x").contains("多筆"));
            assertEquals("", repository.find(b).orElseThrow().title());
            assertTrue(run(repository, UUID.randomUUID().toString(), "x").contains("找不到"));
        }
    }

    @Test
    public void refusesAnInvalidTitleWithoutWriting() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (ProfileRepository repository = open(profile(uuid, "Sunny"))) {
            String message = run(repository, "Sunny", "x".repeat(TitleAdmin.MAX_LENGTH + 1));
            assertTrue(message.contains("最長"));
            assertEquals("", repository.find(uuid).orElseThrow().title());
        }
    }
}
