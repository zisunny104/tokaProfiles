package dev.toka.profiles;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayLinkTest {
    @Test public void aTitleGetsTheSeparatorAndNoTitleGetsNothing() {
        assertEquals("村長§6-", DisplayLink.text("村長", "§6-"));
        assertEquals("", DisplayLink.text("", "§6-"));
        assertEquals("", DisplayLink.text(null, "§6-"));
    }
}
