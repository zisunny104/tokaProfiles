package dev.toka.profiles.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import java.util.UUID;
import org.junit.Test;

public class LegacyResolvedEventTest {
    @Test public void carriesTheNameAndTheAccount() {
        UUID id = UUID.randomUUID();
        var event = new LegacyResolvedEvent("Alone", id);
        assertEquals("Alone", event.getOldName());
        assertEquals(id, event.getUuid());
    }

    @Test public void allEventsShareOneHandlerList() {
        assertNotNull(LegacyResolvedEvent.getHandlers());
        assertSame(LegacyResolvedEvent.getHandlers(), LegacyResolvedEvent.getHandlers());
    }
}
