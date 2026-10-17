package dev.toka.profiles;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

public class ConfigEditTest {
    private static final char BS = (char) 92;
    @Test public void changesOnlyTheNamedValuesAndKeepsComments() throws Exception {
        Path root = Path.of(".build-tools/tests");
        Files.createDirectories(root);
        Path file = Files.createTempDirectory(root, "config-edit-").resolve("config.yml");
        String original = "# top comment\r\nstorage:\r\n  # path comment\r\n  active-directory: storage # inline\r\n  backend: sqlite\r\nstaged:\r\n  enabled: true\r\n  # kept\r\n  activate-next-restart: true\r\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("storage.active-directory", ConfigEdit.quote("a" + BS + "b'c"));
        changes.put("staged.enabled", "false");
        changes.put("staged.activate-next-restart", "false");
        changes.put("migration.stage", "imported");
        ConfigEdit.apply(file, changes);
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result, result.contains("# top comment\r\n"));
        assertTrue(result, result.contains("  # path comment\r\n"));
        assertTrue(result, result.contains("  active-directory: 'a" + BS + "b''c' # inline\r\n"));
        assertTrue(result, result.contains("  backend: sqlite\r\n"));
        assertTrue(result, result.contains("  enabled: false\r\n"));
        assertTrue(result, result.contains("  # kept\r\n  activate-next-restart: false\r\n"));
        assertTrue(result, result.contains("migration:\r\n  stage: imported"));
        Object parsed = new org.yaml.snakeyaml.Yaml().load(result);
        Map<?, ?> storage = (Map<?, ?>) ((Map<?, ?>) parsed).get("storage");
        assertEquals("a" + BS + "b'c", storage.get("active-directory"));
    }
}
