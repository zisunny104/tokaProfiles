package dev.toka.profiles;

import dev.toka.i18n.Messages;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Button labels carry no colour codes; only a leading red code is allowed (close, cancel). */
public class ButtonTextTest {
    private static final String[] BUTTON_KEYS = {
            "ui.back", "ui.cancel", "ui.admin.accept", "ui.admin.btnNotes", "ui.admin.btnOverview", "ui.admin.btnLegacy",
            "ui.overview.btn.title", "ui.overview.btn.stats", "ui.overview.btn.wallet", "ui.overview.btn.land", "ui.overview.btn.checkin",
            "ui.stats.range", "ui.admin.legacy.confirm", "ui.admin.legacy.other", "ui.admin.legacy.item",
            "welcome.next", "welcome.start", "citypass.back", "citypass.close",
            "citypass.main.teleport", "citypass.main.wallet", "citypass.main.shop", "citypass.main.land", "citypass.main.profile",
            "citypass.main.status", "citypass.main.help", "citypass.main.admin"};

    @Test public void buttonLabelsHaveNoColourCodesExceptLeadingRed() {
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", new ArrayList<String>()::add);
        for (String key : BUTTON_KEYS) {
            assertTrue("missing " + key, messages.has(key));
            String text = messages.text(key, "a", "b", "c");
            String rest = text.startsWith("§c") ? text.substring(2) : text;
            assertFalse(key + " has a colour code: " + text, rest.contains("§"));
        }
    }
}
