package dev.toka.profiles;

import dev.toka.profiles.view.Chart;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ChartTest {
    @Test public void barFillsValueOverMaxRounded() {
        assertEquals("■■■■■□□□□□", Chart.bar(50, 100));
        assertEquals("■■■■■■■■■■", Chart.bar(100, 100));
        assertEquals("■■■□□□□□□□", Chart.bar(26, 100));
        assertEquals("□□□□□□□□□□", Chart.bar(4, 100));
        assertEquals("■■■■■", Chart.bar(10, 10, 5));
    }

    @Test public void emptyZeroAndOverflowingDataStayInsideTheBar() {
        assertEquals("□□□□□□□□□□", Chart.bar(0, 0));
        assertEquals("□□□□□□□□□□", Chart.bar(0, 100));
        assertEquals("□□□□□□□□□□", Chart.bar(-5, 100));
        assertEquals("□□□□□□□□□□", Chart.bar(7, 0));
        assertEquals("■■■■■■■■■■", Chart.bar(500, 100));
    }

    @Test public void largeNumbersDoNotOverflow() {
        assertEquals("■■■■■□□□□□", Chart.bar(Long.MAX_VALUE / 2, Long.MAX_VALUE));
        assertEquals("9,223,372,036,854,775,807", Chart.commas(Long.MAX_VALUE));
        assertEquals("▲ 1,000", Chart.change(1000));
        assertEquals("▼ 1,234,567", Chart.change(-1234567));
        assertEquals("0", Chart.change(0));
    }

    @Test public void checkInStripMarksTheStreakDays() {
        assertEquals("□□□□■■■", Chart.strip(Chart.checkInDays(100, 100, 3, 7)));
        assertEquals("□□□□□□□", Chart.strip(Chart.checkInDays(100, 100, 0, 7)));
        assertEquals("□□□■■□□", Chart.strip(Chart.checkInDays(100, 98, 2, 7)));
        assertEquals("■■■■■■■", Chart.strip(Chart.checkInDays(100, 100, 30, 7)));
    }

    @Test public void streakBreaksWhenTheLastClaimIsOlderThanYesterday() {
        assertEquals(5, Chart.liveStreak(100, 100, 5));
        assertEquals(5, Chart.liveStreak(100, 99, 5));
        assertEquals(0, Chart.liveStreak(100, 98, 5));
    }

    @Test public void balancesAreRebuiltBackwardsFromTheCurrentOne() {
        assertEquals(List.of(70L, 100L, 160L), Chart.balancesAfter(160, List.of(60L, 30L, -30L), 3));
        assertEquals(List.of(160L), Chart.balancesAfter(160, List.of(60L), 5));
        assertTrue(Chart.balancesAfter(5, List.of(), 5).isEmpty());
        assertEquals(2, Chart.balancesAfter(5, List.of(1L, 1L, 1L), 2).size());
    }
}
