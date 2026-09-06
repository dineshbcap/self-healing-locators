package com.dinesh.healing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects every healing event during a run and writes target/healing-report.json
 * at suite end. Jenkins post-build steps can parse that file to post a
 * "3 locators healed - POM updates recommended" summary.
 *
 * A pluggable {@link Listener} lets the parent framework mirror each event
 * into ExtentReports (or Allure, or a Slack webhook) without this module
 * depending on any reporting library:
 *
 * <pre>
 * HealingReporter.addListener(record ->
 *     extentTest.warning("Locator healed: " + record.locatorKey()
 *         + " " + record.originalLocator() + " -> " + record.healedLocator()));
 * </pre>
 */
public final class HealingReporter {

    private static final Logger LOG = LoggerFactory.getLogger(HealingReporter.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public record HealingRecord(
            String timestamp,
            String locatorKey,
            /** "android" or "ios" - locator keys are shared across both platform properties files by
             * convention, so Phase 4 needs this to avoid patching the wrong platform's file with a
             * strategy/value that belongs to the other one. */
            String platform,
            String description,
            String originalLocator,
            String healedLocator,
            /** {@link LocatorStrategy#json()} of the healed locator (e.g. "xpath") - Phase 4 writes
             * this straight back into a locators_&lt;platform&gt;.properties line. Blank if the
             * strategy could not be recovered from a raw {@link org.openqa.selenium.By}. */
            String healedStrategy,
            /** Raw locator value paired with {@link #healedStrategy}, e.g. the xpath expression itself. */
            String healedValue,
            String healingStrategy,
            String source) {
    }

    @FunctionalInterface
    public interface Listener {
        void onHeal(HealingRecord record);
    }

    private static final List<HealingRecord> RECORDS = new CopyOnWriteArrayList<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private HealingReporter() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void record(LocatorDef def, Platform platform, String healedLocator,
                              LocatorStrategy healedStrategy, String healedValue,
                              String healingStrategy, String source) {
        HealingRecord record = new HealingRecord(
                Instant.now().toString(),
                def.key(),
                platform.name().toLowerCase(java.util.Locale.ROOT),
                def.description(),
                def.toString(),
                healedLocator,
                healedStrategy == null ? "" : healedStrategy.json(),
                healedValue == null ? "" : healedValue,
                healingStrategy,
                source);
        RECORDS.add(record);
        LOG.warn("LOCATOR HEALED [{}] {} -> {} (via {})",
                def.key(), def.strategy().json() + "=" + def.value(), healedLocator, healingStrategy);
        for (Listener listener : LISTENERS) {
            try {
                listener.onHeal(record);
            } catch (RuntimeException e) {
                LOG.debug("Healing listener threw: {}", e.toString());
            }
        }
    }

    public static List<HealingRecord> records() {
        return List.copyOf(RECORDS);
    }

    public static int healCount() {
        return RECORDS.size();
    }

    /** Call from @AfterSuite. Safe to call when no heals occurred. */
    public static void writeReport(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writeValueAsString(new ArrayList<>(RECORDS)));
            LOG.info("Healing report written: {} ({} heal(s))", file, RECORDS.size());
        } catch (IOException e) {
            LOG.warn("Could not write healing report: {}", e.toString());
        }
    }

    /**
     * Appends one CSV row (timestamp, build id, heal count) for this run - the
     * raw data behind a healing-rate-per-release trend. Call alongside
     * {@link #writeReport} from the same {@code @AfterSuite} hook. Safe to call
     * every run, including zero-heal runs - a flat trend line is itself signal.
     */
    public static void appendMetric(String buildId, Path metricsFile) {
        try {
            Files.createDirectories(metricsFile.toAbsolutePath().getParent());
            boolean writeHeader = Files.notExists(metricsFile);
            StringBuilder line = new StringBuilder();
            if (writeHeader) {
                line.append("timestamp,buildId,healCount\n");
            }
            line.append(Instant.now()).append(',').append(csvSafe(buildId)).append(',').append(RECORDS.size())
                    .append('\n');
            Files.writeString(metricsFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Could not append healing metric: {}", e.toString());
        }
    }

    /**
     * Appends one CSV row per heal from this run (timestamp, build id, platform, locator
     * key, healing strategy) - {@link #appendMetric} only gives a per-run total, which
     * can't tell you WHICH screen is churning. {@link HealingChurnAnalyzer} (Phase 5)
     * reads this file back to rank screens by heal frequency. Call alongside
     * {@link #writeReport}/{@link #appendMetric} from the same {@code @AfterSuite} hook.
     * A zero-heal run appends nothing - there's no event to log.
     */
    public static void appendEvents(String buildId, Path eventsFile) {
        if (RECORDS.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(eventsFile.toAbsolutePath().getParent());
            boolean writeHeader = Files.notExists(eventsFile);
            StringBuilder lines = new StringBuilder();
            if (writeHeader) {
                lines.append("timestamp,buildId,platform,locatorKey,healingStrategy\n");
            }
            for (HealingRecord record : RECORDS) {
                lines.append(record.timestamp()).append(',')
                        .append(csvSafe(buildId)).append(',')
                        .append(csvSafe(record.platform())).append(',')
                        .append(csvSafe(record.locatorKey())).append(',')
                        .append(csvSafe(record.healingStrategy())).append('\n');
            }
            Files.writeString(eventsFile, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Could not append healing events: {}", e.toString());
        }
    }

    private static String csvSafe(String s) {
        return (s == null ? "unknown" : s).replace(",", "_").replace("\n", " ");
    }

    /** For tests. */
    static void reset() {
        RECORDS.clear();
        LISTENERS.clear();
    }
}
