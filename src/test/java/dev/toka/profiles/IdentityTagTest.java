package dev.toka.profiles;

import static org.junit.Assert.assertEquals;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class IdentityTagTest {
    private static final Map<String, String> TAGS = Map.of("admin", "§b[管]", "sponsor", "§e[特]", "linked", "", "ordinary", "§6[普]");

    private static String tag(String... nodes) { return DisplayLink.identity(Set.of(nodes)::contains, TAGS); }

    @Test public void anOrdinaryPlayerShowsTheOrdinaryTag() { assertEquals("§6[普]", tag()); }

    @Test public void identitiesAreKeptAsPlainWords() { assertEquals("admin,sponsor", DisplayLink.identities(n -> !n.endsWith("linked"))); }

    @Test public void identitiesStackInAFixedOrder() {
        assertEquals("§b[管]§e[特]", tag("toka.identity.sponsor", "toka.identity.admin"));
    }

    @Test public void aBlankTagIsNotShown() { assertEquals("§b[管]", tag("toka.identity.linked", "toka.identity.admin")); }

    @Test public void theOrdinaryTagShowsOnlyWithoutAnyIdentity() {
        var tags = Map.of("sponsor", "特", "ordinary", "普");
        assertEquals("普", DisplayLink.identity(n -> false, tags));
        assertEquals("特", DisplayLink.identity("toka.identity.sponsor"::equals, tags));
    }
}
