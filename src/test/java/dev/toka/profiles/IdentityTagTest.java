package dev.toka.profiles;

import static org.junit.Assert.assertEquals;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class IdentityTagTest {
    private static final Map<String, String> TAGS = Map.of("admin", "§c管", "sponsor", "§6特", "linked", "", "ordinary", "");

    private static String tag(String... nodes) { return DisplayLink.identity(Set.of(nodes)::contains, TAGS); }

    @Test public void anOrdinaryPlayerShowsNothingByDefault() { assertEquals("", tag()); }

    @Test public void identitiesStackInAFixedOrder() {
        assertEquals("§8[§c管§6特§8]", tag("toka.identity.sponsor", "toka.identity.admin"));
    }

    @Test public void aBlankTagIsNotShown() { assertEquals("", tag("toka.identity.linked")); }

    @Test public void theOrdinaryTagShowsOnlyWithoutAnyIdentity() {
        var tags = Map.of("sponsor", "特", "ordinary", "普");
        assertEquals("§8[普§8]", DisplayLink.identity(n -> false, tags));
        assertEquals("§8[特§8]", DisplayLink.identity("toka.identity.sponsor"::equals, tags));
    }
}
