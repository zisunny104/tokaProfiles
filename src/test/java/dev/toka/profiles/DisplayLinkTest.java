package dev.toka.profiles;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayLinkTest {
    @Test public void aTitleGetsTheSeparatorAndNoTitleGetsNothing() {
        assertEquals("村長§6-", DisplayLink.text("村長", "§6-"));
        assertEquals("", DisplayLink.text("", "§6-"));
        assertEquals("", DisplayLink.text(null, "§6-"));
    }

    @Test public void theNameTagReadsIdentityTitleThenName() {
        var tags = java.util.Map.of("admin", "§b[管]", "sponsor", "§e[特]", "linked", "", "ordinary", "§6[普]");
        assertEquals("§b[管]村長§6-§rAlex", DisplayLink.nameTag(java.util.List.of("admin"), tags, "村長", "§6-", "Alex"));
        assertEquals("§6[普]§rAlex", DisplayLink.nameTag(java.util.List.of(), tags, "", "§6-", "Alex"));
        assertEquals("§e[特]§rAlex", DisplayLink.nameTag(java.util.List.of("sponsor", "linked"), tags, null, "§6-", "Alex"));
    }
}
