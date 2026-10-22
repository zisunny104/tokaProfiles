package dev.toka.profiles;

import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.ProfileCodec;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AffiliationTest {
    private static Profile profile() {
        return new Profile(UUID.randomUUID(), "Alex", Set.of(), Map.of(), null);
    }

    @Test public void aProfileStartsWithoutAnAffiliation() {
        assertEquals("", profile().affiliation());
    }

    @Test public void affiliationSurvivesEncodingAndTitleChanges() {
        String region = UUID.randomUUID().toString();
        Profile affiliated = profile().withAffiliation(region).withTitle("Mayor");
        assertEquals(region, affiliated.affiliation());
        Profile decoded = ProfileCodec.decode(ProfileCodec.encode(affiliated));
        assertEquals(region, decoded.affiliation());
        assertEquals("Mayor", decoded.title());
    }

    @Test public void oldDocumentsWithoutTheFieldStillLoad() {
        var encoded = ProfileCodec.encode(profile());
        assertFalse(encoded.has("affiliation"));
        assertEquals("", ProfileCodec.decode(encoded).affiliation());
    }

    @Test public void clearingAnAffiliationUsesTheEmptyString() {
        Profile cleared = profile().withAffiliation(UUID.randomUUID().toString()).withAffiliation("");
        assertEquals("", cleared.affiliation());
    }

    @Test(expected = IllegalArgumentException.class) public void aNameIsNotAnAffiliation() {
        profile().withAffiliation("Taipei");
    }

    @Test(expected = IllegalArgumentException.class) public void aNonCanonicalIdIsRejected() {
        profile().withAffiliation(UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
    }
}
