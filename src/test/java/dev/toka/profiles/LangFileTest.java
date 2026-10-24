package dev.toka.profiles;

import dev.toka.i18n.Messages;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class LangFileTest {
    private static final Pattern KEY = Pattern.compile("[\"]((?:cmd|help|msg|err|ui|log|status)[.][A-Za-z0-9_.]*[A-Za-z0-9_])[\"]");

    @Test public void everyKeyInTheFileCanBeRead() throws IOException {
        Set<String> keys = fileKeys();
        assertTrue(keys.size() > 30);
        List<String> warnings = new ArrayList<>();
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", warnings::add);
        for (String key : keys) {
            assertTrue("missing " + key, messages.has(key));
            assertFalse("empty " + key, messages.lines(key, "a", "b", "c", "d", "e").isEmpty());
        }
        assertTrue(warnings.toString(), warnings.isEmpty());
    }

    @Test public void everyKeyUsedBySourceExistsInTheFile() throws IOException {
        Set<String> keys = fileKeys();
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertFalse(used.isEmpty());
        for (String key : used) {
            assertTrue("source uses a key missing from zh-TW.yml: " + key, keys.contains(key));
        }
    }

    @Test public void helpLinesAreTagged() {
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", line -> { });
        for (String line : messages.lines("help.profiles", 255)) {
            int bar = line.indexOf('|');
            assertTrue("untagged help line: " + line, bar > 0);
            String tag = line.substring(0, bar);
            assertTrue("unknown tag " + tag, java.util.Set.of("*", "title", "self", "note", "legacy", "plan").contains(tag));
        }
    }

    @Test public void keysBuiltAtRunTimeExist() throws IOException {
        Set<String> keys = fileKeys();
        for (String section : List.of("base", "wallet", "checkin", "land", "discord", "notes", "legacy", "flow", "trend")) assertTrue(section, keys.contains("ui.overview.sec." + section));
        for (String id : List.of("admin", "sponsor", "linked", "ordinary")) assertTrue(id, keys.contains("citypass.identity." + id));
        for (String section : List.of("teleport", "land", "wallet", "shop", "profile", "rules")) {
            assertTrue(section, keys.contains("citypass.help." + section + ".label"));
            assertTrue(section, keys.contains("citypass.help." + section + ".title"));
            assertTrue(section, keys.contains("citypass.help." + section + ".body"));
        }
        for (String kind : List.of("limit", "limitColour", "label", "placeholder", "clear", "current", "title")) assertTrue(kind, keys.contains("citypass.titleEdit." + kind));
    }

    private Set<String> fileKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        Object root = new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of("src", "main", "resources", "lang", "zh-TW.yml"), StandardCharsets.UTF_8));
        flatten("", (Map<?, ?>) root, keys);
        return keys;
    }

    private static void flatten(String prefix, Map<?, ?> map, Set<String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) flatten(key, child, out);
            else out.add(key);
        }
    }
}
