package dev.toka.profiles;

import dev.toka.i18n.Pager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * The chat help: lines of the language file look like "tag|text"; a tag decides who sees the line ("*" is everybody). The first line
 * is the title, the rest is cut into pages of a fixed size. Anything that is not a page number shows the first page.
 */
final class HelpPages {
    private HelpPages() {}

    /**
     * @param tagged  the lines of the language list, "tag|text"
     * @param allowed whether the reader may see lines with this tag
     * @param footer  the line under a page of a longer help, from the page and the number of pages
     */
    static List<String> render(List<String> tagged, Predicate<String> allowed, String pageArgument, int perPage, BiFunction<Integer, Integer, String> footer) {
        List<String> visible = new ArrayList<>();
        for (String line : tagged) {
            int bar = line.indexOf('|');
            if (bar < 0) visible.add(line);
            else if (allowed.test(line.substring(0, bar))) visible.add(line.substring(bar + 1));
        }
        if (visible.isEmpty()) return visible;
        Pager.Page<String> page = Pager.of(visible.subList(1, visible.size()), Math.max(3, perPage), Pager.parse(pageArgument, 1));
        List<String> out = new ArrayList<>();
        out.add(visible.get(0));
        out.addAll(page.lines());
        if (page.pages() > 1) out.add(footer.apply(page.page(), page.pages()));
        return out;
    }
}
