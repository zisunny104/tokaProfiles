package dev.toka.profiles;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverviewTimeTest {
    @Test
    public void secondsOfTheCoreAreShownAsTheirDate() {
        // 2026-10-27 in seconds, as the core keeps the first and the last time played
        assertTrue(Overview.time(1_793_000_000L), Overview.time(1_793_000_000L).startsWith("2026-10-"));
    }

    @Test
    public void millisecondsGiveTheSameDateAsSeconds() {
        assertEquals(Overview.time(1_793_000_000L), Overview.time(1_793_000_000_000L));
    }
}
