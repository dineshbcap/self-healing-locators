package com.dinesh.healing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5: turns the accumulated {@link HealingReporter#appendEvents} log into a
 * per-screen "UI churn" ranking - screens that heal constantly across many builds are
 * either unstable or under-owned, which is a different signal than "how many heals
 * happened today" (Phase 3's aggregate count can't distinguish one screen healing 10
 * times from 10 screens healing once each).
 *
 * "Screen" is the portion of a locator key before the first '.', matching this
 * project's {@code screen.element} key convention (see locators_android.properties).
 *
 * This can only rank screens that healed at least once somewhere in the log - it has
 * no way to enumerate "screens that never heal" without access to the full locator
 * repository, which this analyzer never reads. Absence from the ranking means "no
 * recorded heals", not "definitely stable".
 */
public final class HealingChurnAnalyzer {

    /**
     * @param screen        the screen.element key's screen segment
     * @param totalHeals    every heal event recorded for this screen, across all builds
     * @param distinctBuilds how many distinct build ids contributed at least one heal -
     *                      the stronger churn signal: 1 build healing the same locator
     *                      5 times (a flaky run) looks very different from 5 separate
     *                      builds each healing it once (genuine ongoing instability)
     * @param locatorKeys   distinct locator keys under this screen that ever healed
     * @param lastHealedTimestamp the most recent event's timestamp
     */
    public record ScreenChurn(String screen, int totalHeals, int distinctBuilds,
                              List<String> locatorKeys, String lastHealedTimestamp) {
    }

    private record Event(String timestamp, String buildId, String locatorKey) {
    }

    private HealingChurnAnalyzer() {
    }

    /** Parses {@code timestamp,buildId,platform,locatorKey,healingStrategy} CSV text (header optional). */
    public static List<ScreenChurn> analyze(String eventsCsv) {
        Map<String, List<Event>> byScreen = new LinkedHashMap<>();
        for (String line : eventsCsv.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("timestamp,buildId,")) {
                continue; // blank line or header
            }
            String[] cols = trimmed.split(",", -1);
            if (cols.length < 4) {
                continue; // malformed row - skip rather than guess
            }
            String timestamp = cols[0];
            String buildId = cols[1];
            String locatorKey = cols[3];
            byScreen.computeIfAbsent(screenOf(locatorKey), s -> new ArrayList<>())
                    .add(new Event(timestamp, buildId, locatorKey));
        }

        List<ScreenChurn> result = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : byScreen.entrySet()) {
            List<Event> events = entry.getValue();
            Set<String> distinctBuilds = new LinkedHashSet<>();
            Set<String> distinctKeys = new LinkedHashSet<>();
            String lastTimestamp = "";
            for (Event e : events) {
                distinctBuilds.add(e.buildId());
                distinctKeys.add(e.locatorKey());
                if (e.timestamp().compareTo(lastTimestamp) > 0) {
                    lastTimestamp = e.timestamp();
                }
            }
            result.add(new ScreenChurn(entry.getKey(), events.size(), distinctBuilds.size(),
                    List.copyOf(distinctKeys), lastTimestamp));
        }

        result.sort(Comparator
                .comparingInt(ScreenChurn::distinctBuilds).reversed()
                .thenComparing(Comparator.comparingInt(ScreenChurn::totalHeals).reversed())
                .thenComparing(ScreenChurn::screen));
        return result;
    }

    static String screenOf(String locatorKey) {
        int dot = locatorKey.indexOf('.');
        return dot > 0 ? locatorKey.substring(0, dot) : locatorKey;
    }

    /** Plain-text ranked report, e.g. for a Jenkins console or a webhook message body. */
    public static String renderReport(List<ScreenChurn> churns, int topN) {
        if (churns.isEmpty()) {
            return "No healing events recorded - nothing to analyze.";
        }
        StringBuilder sb = new StringBuilder("Healing churn by screen (top ")
                .append(Math.min(topN, churns.size())).append(" of ").append(churns.size()).append("):\n");
        int shown = Math.min(topN, churns.size());
        for (int i = 0; i < shown; i++) {
            ScreenChurn c = churns.get(i);
            sb.append(String.format(Locale.ROOT, "%d. %-20s %3d heal(s) across %d build(s) - keys: %s%n",
                    i + 1, c.screen(), c.totalHeals(), c.distinctBuilds(), String.join(", ", c.locatorKeys())));
        }
        if (churns.size() > shown) {
            sb.append("...and ").append(churns.size() - shown).append(" more screen(s)\n");
        }
        return sb.toString().stripTrailing();
    }
}
