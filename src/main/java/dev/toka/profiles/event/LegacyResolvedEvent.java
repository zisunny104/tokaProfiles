package dev.toka.profiles.event;

import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;
import java.util.UUID;

/**
 * An old name from the previous server's data has been taken over by an account (at login, or when an administrator confirmed it).
 * The decision is already saved when this fires.
 *
 * <p>Fires on the MAIN server thread, once per name, only for takeovers that happen after tokaProfiles started; what was taken over
 * earlier is read from {@code Main.legacyResolved()} when the listening plugin starts. Nothing is fired for the quiet import of
 * accounts that were already known.
 *
 * <p>Handlers must be quick and work for THIS one player only: never scan every claim, region or account, never block on disk or the
 * network, and hand any saving to the plugin's own async or batched path. A handler's exception is logged by the plugin manager and
 * affects nobody else. Do not cancel or change anything here; it is a notification.
 */
public class LegacyResolvedEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final String oldName;
    private final UUID uuid;

    public LegacyResolvedEvent(String oldName, UUID uuid) {
        this.oldName = oldName;
        this.uuid = uuid;
    }

    public static HandlerList getHandlers() { return handlers; }

    /** The old name, as the old data spelled it. */
    public String getOldName() { return oldName; }

    /** The account that now holds the name. */
    public UUID getUuid() { return uuid; }
}
