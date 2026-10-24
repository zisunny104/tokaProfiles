package dev.toka.profiles.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Small text charts for windows. Only characters the Bedrock default font draws: the squares and triangles of the Geometric Shapes
 * block, and plain digits. No server types in here, so the numbers can be tested.
 */
public final class Chart {
    public static final char FULL = '■', EMPTY = '□', UP = '▲', DOWN = '▼';
    public static final int WIDTH = 10;

    private Chart() {}

    /** The value as filled cells out of {@code width}: value / max, rounded. No value or no maximum gives an empty bar. */
    public static String bar(long value, long max, int width) {
        int filled = 0;
        if (value > 0 && max > 0) filled = (int) Math.max(0, Math.min(width, Math.round((double) value / (double) max * width)));
        return String.valueOf(FULL).repeat(filled) + String.valueOf(EMPTY).repeat(width - filled);
    }

    public static String bar(long value, long max) { return bar(value, max, WIDTH); }

    /** One cell per day, oldest first: filled when that day counts. */
    public static String strip(List<Boolean> days) {
        StringBuilder out = new StringBuilder();
        for (boolean day : days) out.append(day ? FULL : EMPTY);
        return out.toString();
    }

    /** The change with its sign and a triangle: up for a gain, down for a loss, nothing for no change. */
    public static String change(long delta) {
        if (delta == 0) return "0";
        return (delta > 0 ? String.valueOf(UP) : String.valueOf(DOWN)) + " " + commas(Math.abs(delta));
    }

    public static String commas(long value) { return String.format("%,d", value); }

    /**
     * Which of the last {@code days} days (oldest first, ending today) were checked in, from the last day claimed and the streak that
     * ended on it. Days are counted back from today as numbers, so the caller passes {@code today} and {@code last} as epoch days.
     */
    public static List<Boolean> checkInDays(long today, long last, int streak, int days) {
        List<Boolean> out = new ArrayList<>();
        for (int back = days - 1; back >= 0; back--) {
            long day = today - back;
            out.add(streak > 0 && day <= last && day > last - streak);
        }
        return out;
    }

    /** The streak that still counts today: it was broken when the last claim is older than yesterday. */
    public static int liveStreak(long today, long last, int streak) {
        return last >= today - 1 ? streak : 0;
    }

    /**
     * The balances after each of the newest movements, oldest first. {@code amounts} are the signed movements newest first; the newest
     * balance is the current one, the one before it is the current minus the newest movement, and so on.
     */
    public static List<Long> balancesAfter(long current, List<Long> amounts, int points) {
        List<Long> newestFirst = new ArrayList<>();
        long balance = current;
        for (int i = 0; i < amounts.size() && i < points; i++) {
            newestFirst.add(balance);
            balance -= amounts.get(i);
        }
        List<Long> out = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) out.add(newestFirst.get(i));
        return out;
    }
}
