package dev.toka.profiles;

import dev.toka.profiles.command.SelfTitle;
import org.junit.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class SelfTitleTest {
    private static final SelfTitle.Rules RULES = new SelfTitle.Rules(6, 300_000, List.of("Admin", "bad word"));
    private static final SelfTitle.Rules WIDE = new SelfTitle.Rules(30, 300_000, List.of("Admin", "bad word"));

    @Test public void aShortPlainTitleIsAccepted() {
        assertNull(SelfTitle.check("村長", false, RULES));
    }

    @Test public void lengthIgnoresColourCodes() {
        assertNull(SelfTitle.check("&a村長大人", true, RULES));
        assertEquals("err.title.selfLong", SelfTitle.check("一二三四五六七", false, RULES).key());
    }

    @Test public void coloursNeedTheirOwnPermission() {
        assertEquals("err.title.selfColour", SelfTitle.check("&a村長", false, RULES).key());
        assertEquals("err.title.selfColour", SelfTitle.check("§a村長", false, RULES).key());
        assertNull(SelfTitle.check("&a村長", true, RULES));
    }

    @Test public void blockedWordsAreFoundIgnoringCaseSpacesAndColour() {
        assertEquals("err.title.selfBlocked", SelfTitle.check("ADMIN", false, WIDE).key());
        assertEquals("err.title.selfBlocked", SelfTitle.check("a d m i n", false, WIDE).key());
        assertEquals("err.title.selfBlocked", SelfTitle.check("&cbad&r word", true, WIDE).key());
    }

    @Test public void blankAndHiddenCharactersAreRefused() {
        assertEquals("err.title.selfBlank", SelfTitle.check("   ", false, RULES).key());
        assertEquals("err.title.selfBlank", SelfTitle.check("&a", true, RULES).key());
        assertEquals("err.title.control", SelfTitle.check("a\nb", false, RULES).key());
    }

    @Test public void theWaitCountsDownAndEndsAfterTheCooldown() {
        var titles = new SelfTitle();
        UUID player = UUID.randomUUID();
        assertEquals(0, titles.waitSeconds(player, 1000, 300_000));
        titles.changed(player, 1000);
        assertEquals(300, titles.waitSeconds(player, 1000, 300_000));
        assertEquals(1, titles.waitSeconds(player, 1000 + 299_001, 300_000));
        assertEquals(0, titles.waitSeconds(player, 1000 + 300_000, 300_000));
    }

    @Test(expected = IllegalArgumentException.class) public void aZeroLengthLimitIsRejected() {
        new SelfTitle.Rules(0, 0, List.of());
    }
}
