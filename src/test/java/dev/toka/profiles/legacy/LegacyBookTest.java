package dev.toka.profiles.legacy;

import static org.junit.Assert.*;
import dev.toka.storage.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class LegacyBookTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path dir() { return folder.getRoot().toPath(); }
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    private LegacyBook book() throws Exception {
        try (var seed = DocumentStore.open(new StoreConfig(Backend.JSON, dir(), 1))) {
            for (var r : List.of(
                    new LegacyRecord("Alone", "1", null, "", "", "", Set.of(), Set.of(), null, ""),
                    new LegacyRecord("Shared", "2", null, "", "", "", Set.of(B), Set.of(), null, ""),
                    new LegacyRecord("Known", "3", A, "", "", "", Set.of(A), Set.of(), null, ""))) seed.save(LegacyBook.storeKey(r.name()), r.toJson());
        }
        var book = LegacyBook.open(new StoreConfig(Backend.JSON, dir(), 1));
        return book;
    }

    @Test public void exactNameWithNoOtherAccountIsTakenOver() throws Exception {
        try (var book = book()) {
            assertEquals(LegacyBook.Verdict.CLAIM, LegacyBook.judge(book.find("alone").get(), "Alone", A));
            assertEquals(LegacyBook.Verdict.CONFIRM_NEEDED, LegacyBook.judge(book.find("alone").get(), "ALONE", A));
        }
    }

    @Test public void aNameSeenWithAnotherAccountWaitsForAnAdministrator() throws Exception {
        try (var book = book()) {
            assertEquals(LegacyBook.Verdict.CONFIRM_NEEDED, LegacyBook.judge(book.find("Shared").get(), "Shared", A));
            assertEquals(LegacyBook.Verdict.CLAIM, LegacyBook.judge(book.find("Shared").get(), "Shared", B));
        }
    }

    @Test public void aNameThatHadAnAccountBelongsOnlyToIt() throws Exception {
        try (var book = book()) {
            assertEquals(LegacyBook.Verdict.NONE, LegacyBook.judge(book.find("Known").get(), "Known", B));
            assertEquals(LegacyBook.Verdict.CLAIM, LegacyBook.judge(book.find("Known").get(), "Known", A));
        }
    }

    @Test public void aClaimIsKeptAndAnnouncedOnce() throws Exception {
        var heard = new ArrayList<String>();
        try (var book = book()) {
            book.listen((name, id) -> heard.add(name + id));
            assertTrue(book.claim("Alone", A));
            assertFalse(book.claim("Alone", B));
            assertEquals(List.of("Alone" + A), heard);
        }
        try (var again = LegacyBook.open(new StoreConfig(Backend.JSON, dir(), 1))) {
            assertEquals(Map.of("Alone", A), again.resolved());
        }
    }
}
