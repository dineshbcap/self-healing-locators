package com.dinesh.healing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            String description,
            String originalLocator,
            String healedLocator,
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

    public static void record(LocatorDef def, String healedLocator, String strategy, String source) {
        HealingRecord record = new HealingRecord(
                Instant.now().toString(),
                def.key(),
                def.description(),
                def.toString(),
                healedLocator,
                strategy,
                source);
        RECORDS.add(record);
        LOG.warn("LOCATOR HEALED [{}] {} -> {} (via {})",
                def.key(), def.strategy().json() + "=" + def.value(), healedLocator, strategy);
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

    /** For tests. */
    static void reset() {
        RECORDS.clear();
        LISTENERS.clear();
    }
}
