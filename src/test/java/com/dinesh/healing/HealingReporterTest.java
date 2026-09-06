package com.dinesh.healing;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HealingReporterTest {

    @BeforeMethod
    @AfterMethod
    public void resetState() {
        HealingReporter.reset();
    }

    private static LocatorDef def(String key) {
        return new LocatorDef(key, "desc", LocatorStrategy.ID, "com.td.app:id/btn");
    }

    @Test
    public void recordAddsToRecordsAndNotifiesListeners() {
        List<HealingReporter.HealingRecord> seen = new java.util.ArrayList<>();
        HealingReporter.addListener(seen::add);

        HealingReporter.record(def("login.submitButton"), "xpath=//new", "resourceIdContainsFragment", "deterministic");

        assertEquals(HealingReporter.healCount(), 1);
        assertEquals(seen.size(), 1);
        assertEquals(seen.get(0).locatorKey(), "login.submitButton");
        assertEquals(seen.get(0).healedLocator(), "xpath=//new");
    }

    @Test
    public void listenerThrowingDoesNotBreakRecording() {
        HealingReporter.addListener(r -> {
            throw new RuntimeException("listener boom");
        });

        HealingReporter.record(def("k"), "healed", "strategy", "source");

        assertEquals(HealingReporter.healCount(), 1, "Recording must succeed despite a broken listener");
    }

    @Test
    public void writeReportProducesReadableJson() throws IOException {
        HealingReporter.record(def("k"), "healed", "strategy", "source");
        Path file = Files.createTempFile("healing-report", ".json");
        try {
            HealingReporter.writeReport(file);
            String content = Files.readString(file);
            assertTrue(content.contains("\"locatorKey\""));
            assertTrue(content.contains("\"k\""));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void appendMetricWritesHeaderOnceThenAppendsRows() throws IOException {
        Path file = Files.createTempFile("healing-metrics", ".csv");
        Files.deleteIfExists(file); // exercise the "file doesn't exist yet" header path
        try {
            HealingReporter.record(def("k"), "healed", "strategy", "source");
            HealingReporter.appendMetric("build-1.0.0", file);
            HealingReporter.appendMetric("build-1.0.1", file);

            List<String> lines = Files.readAllLines(file);
            assertEquals(lines.get(0), "timestamp,buildId,healCount");
            assertEquals(lines.size(), 3, "Header + two appended rows");
            assertTrue(lines.get(1).contains("build-1.0.0,1"));
            assertTrue(lines.get(2).contains("build-1.0.1,1"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void appendMetricCsvEscapesCommasAndNewlinesInBuildId() throws IOException {
        Path file = Files.createTempFile("healing-metrics", ".csv");
        Files.deleteIfExists(file);
        try {
            HealingReporter.appendMetric("weird,build\nid", file);
            String content = Files.readString(file);
            assertTrue(content.contains("weird_build id"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
