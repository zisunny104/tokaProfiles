package dev.toka.profiles;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class HelpPagesTest {
    private static final List<String> LINES = List.of("*|title", "*|a", "*|b", "*|c", "note|n1", "note|n2", "note|n3", "*|d");

    @Test public void pagesKeepTheTitleAndAddAFooter() {
        var first = HelpPages.render(LINES, tag -> true, null, 3, (p, n) -> "page " + p + "/" + n);
        assertEquals(List.of("title", "a", "b", "c", "page 1/3"), first);
        var second = HelpPages.render(LINES, tag -> true, "2", 3, (p, n) -> "page " + p + "/" + n);
        assertEquals(List.of("title", "n1", "n2", "n3", "page 2/3"), second);
    }

    @Test public void aWordInsteadOfAPageShowsTheFirstPage() {
        assertEquals(HelpPages.render(LINES, tag -> true, null, 3, (p, n) -> "f"), HelpPages.render(LINES, tag -> true, "note", 3, (p, n) -> "f"));
    }

    @Test public void tagsHideLinesFromPeopleWithoutThePermission() {
        var ordinary = HelpPages.render(LINES, Set.of("*")::contains, "1", 8, (p, n) -> "f");
        assertEquals(List.of("title", "a", "b", "c", "d"), ordinary);
    }
}
