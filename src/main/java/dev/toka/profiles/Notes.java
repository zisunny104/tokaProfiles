package dev.toka.profiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The note of a player is text for people that machines can read as well. Each line may be an entry:
 * <pre>donation amount=500 id=20261024-1 date=2026-10-24 # what a person reads</pre>
 * the type first, then {@code key=value} pairs without spaces, then {@code #} and any text. A line that does not start like that is
 * plain text and only for people. Plugins ask for the entries of a type; nobody parses the text.
 */
public final class Notes {
    public record Entry(String type, Map<String, String> fields, String text) {
        public String line() {
            var out = new StringBuilder(type);
            fields.forEach((key, value) -> out.append(' ').append(key).append('=').append(value));
            if (!text.isEmpty()) out.append(" # ").append(text);
            return out.toString();
        }
    }

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern PAIR = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*=[^\\s#]+");

    private Notes() {}

    /** The entries of a note, in order; plain text lines are left out. */
    public static List<Entry> entries(String note) {
        List<Entry> out = new ArrayList<>();
        for (String line : note.split("\\R")) {
            Entry entry = parse(line);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    static Entry parse(String line) {
        String text = "";
        String head = line.strip();
        int hash = head.indexOf(" # ");
        if (hash >= 0) { text = head.substring(hash + 3).strip(); head = head.substring(0, hash).strip(); }
        else if (head.endsWith(" #")) head = head.substring(0, head.length() - 2).strip();
        String[] words = head.split("\\s+");
        if (words.length < 2 || !TYPE.matcher(words[0]).matches()) return null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < words.length; i++) {
            if (!PAIR.matcher(words[i]).matches()) return null;
            int eq = words[i].indexOf('=');
            fields.put(words[i].substring(0, eq), words[i].substring(eq + 1));
        }
        return new Entry(words[0], fields, text);
    }

    /** The note with one more line at its end. */
    public static String append(String note, String line) {
        return note.isBlank() ? line : note.stripTrailing() + "\n" + line;
    }
}
