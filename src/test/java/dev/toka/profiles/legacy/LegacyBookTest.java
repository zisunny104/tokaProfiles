package dev.toka.profiles.legacy;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
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
        var book = LegacyBook.open(new StoreConfig(Backend.JSON, dir(), 1));
        book.importRows(JsonParser.parseString("[{\"name\":\"Alone\",\"id\":\"1\"},{\"name\":\"Shared\",\"id\":\"2\",\"evidence\":[\"" + B + "\"]},{\"name\":\"Known\",\"id\":\"3\",\"UUID\":\"" + A + "\"}]").getAsJsonArray());
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
