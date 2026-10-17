package dev.toka.profiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Changes single values of config.yml in place, line by line, so the comments of the file stay as they are
 * (saving through the config API would drop them). Supports keys of the form {@code section.key}.
 */
public final class ConfigEdit {
    private ConfigEdit() {
    }

    /**
     * @param changes {@code section.key} to the YAML text of the new value (such as {@code false} or {@code 'text'})
     */
    public static void apply(Path file, Map<String, String> changes) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        for (Map.Entry<String, String> change : changes.entrySet()) {
            set(lines, change.getKey(), change.getValue());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, String.join(newline, lines), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Single-quoted YAML text; the only escape inside is a doubled quote, so backslashes are safe. */
    public static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void set(List<String> lines, String path, String value) {
        int dot = path.indexOf('.');
        String section = path.substring(0, dot);
        String key = path.substring(dot + 1);
        int sectionLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(section + ":")) {
                sectionLine = i;
                break;
            }
        }
        if (sectionLine < 0) {
            int last = lines.size();
            while (last > 0 && lines.get(last - 1).isEmpty()) {
                last--;
            }
            lines.add(last, section + ":");
            lines.add(last + 1, "  " + key + ": " + value);
            return;
        }
        int end = sectionLine + 1;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            end++;
        }
        for (int i = sectionLine + 1; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith(key + ":")) {
                String indent = line.substring(0, line.length() - trimmed.length());
                String rest = trimmed.substring(key.length() + 1);
                int comment = rest.indexOf(" #");
                lines.set(i, indent + key + ": " + value + (comment >= 0 ? rest.substring(comment) : ""));
                return;
            }
        }
        int insert = end;
        while (insert > sectionLine + 1 && (lines.get(insert - 1).isEmpty() || lines.get(insert - 1).startsWith("#"))) {
            insert--;
        }
        lines.add(insert, "  " + key + ": " + value);
    }
}
