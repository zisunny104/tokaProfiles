package dev.toka.profiles;

import dev.toka.i18n.Messages;

import java.nio.file.Path;

/**
 * Holder of the plugin's {@link Messages}. The plugin installs its own (language from config.yml) on enable; code that
 * also runs outside the server (command line tools, tests) falls back to the zh-TW texts inside the jar.
 */
public final class Texts {
    private static volatile Messages current;

    private Texts() {
    }

    public static void use(Messages messages) {
        current = messages;
    }

    public static Messages get() {
        Messages messages = current;
        if (messages == null) {
            synchronized (Texts.class) {
                messages = current;
                if (messages == null) {
                    messages = new Messages(Texts.class.getClassLoader(), Path.of(".toka-no-lang-folder"), "zh-TW", System.err::println);
                    current = messages;
                }
            }
        }
        return messages;
    }
}
