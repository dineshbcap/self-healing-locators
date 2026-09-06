package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class HealingReportPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterMethod
    public void tearDown() {
        System.clearProperty("healing.report.webhook.enabled");
        System.clearProperty("healing.report.webhook.url");
        System.clearProperty("healing.report.webhook.format");
        System.clearProperty("healing.report.webhook.maxItems");
    }

    private static HealingReporter.HealingRecord record(String key) {
        return new HealingReporter.HealingRecord(
                "2026-01-01T00:00:00Z", key, "android", "desc", "id=old_" + key, "xpath=//new_" + key,
                "xpath", "//new_" + key,
                "resourceIdContainsFragment", "deterministic");
    }

    @Test
    public void slackPayloadIsPlainTextField() throws IOException {
        String payload = HealingReportPublisher.buildPayload(List.of(record("login.submitButton")), "slack", 20);
        JsonNode node = MAPPER.readTree(payload);
        assertTrue(node.has("text"));
        assertFalse(node.has("@type"), "Slack payload must not carry MessageCard fields");
        assertTrue(node.get("text").asText().contains("login.submitButton"));
        assertTrue(node.get("text").asText().startsWith("1 locator healed"));
    }

    @Test
    public void teamsPayloadIsAlsoPlainTextField() throws IOException {
        String payload = HealingReportPublisher.buildPayload(List.of(record("k")), "teams", 20);
        JsonNode node = MAPPER.readTree(payload);
        assertTrue(node.has("text"));
        assertFalse(node.has("@type"));
    }

    @Test
    public void teamsMessageCardPayloadHasLegacyFields() throws IOException {
        String payload = HealingReportPublisher.buildPayload(List.of(record("k")), "teams-messagecard", 20);
        JsonNode node = MAPPER.readTree(payload);
        assertEquals(node.get("@type").asText(), "MessageCard");
        assertTrue(node.has("summary"));
        assertTrue(node.has("text"));
    }

    @Test
    public void unrecognizedFormatFallsBackToSlackShape() throws IOException {
        String payload = HealingReportPublisher.buildPayload(List.of(record("k")), "bogus", 20);
        JsonNode node = MAPPER.readTree(payload);
        assertTrue(node.has("text"));
        assertFalse(node.has("@type"));
    }

    @Test
    public void summaryTextUsesSingularForOneHeal() {
        String text = HealingReportPublisher.summaryText(List.of(record("k")), 20);
        assertTrue(text.startsWith("1 locator healed"), text);
    }

    @Test
    public void summaryTextUsesPluralAndTruncatesPastMaxItems() {
        List<HealingReporter.HealingRecord> records = List.of(
                record("a"), record("b"), record("c"));
        String text = HealingReportPublisher.summaryText(records, 2);
        assertTrue(text.startsWith("3 locators healed"), text);
        assertTrue(text.contains("...and 1 more"), text);
        assertFalse(text.contains(": id=old_c ->"), "Third item must be truncated, not listed");
    }

    @Test
    public void publishNoOpsWhenWebhookDisabled() throws IOException, InterruptedException {
        System.setProperty("healing.report.webhook.enabled", "false");
        AtomicInteger calls = new AtomicInteger();
        HealingReportPublisher.publish(List.of(record("k")), new HealingConfig(),
                (url, body) -> calls.incrementAndGet());
        assertEquals(calls.get(), 0);
    }

    @Test
    public void publishNoOpsWhenUrlBlank() throws IOException, InterruptedException {
        System.setProperty("healing.report.webhook.enabled", "true");
        System.setProperty("healing.report.webhook.url", "");
        AtomicInteger calls = new AtomicInteger();
        HealingReportPublisher.publish(List.of(record("k")), new HealingConfig(),
                (url, body) -> calls.incrementAndGet());
        assertEquals(calls.get(), 0);
    }

    @Test
    public void publishNoOpsWhenNoHeals() throws IOException, InterruptedException {
        System.setProperty("healing.report.webhook.enabled", "true");
        System.setProperty("healing.report.webhook.url", "https://hooks.example.com/x");
        AtomicInteger calls = new AtomicInteger();
        HealingReportPublisher.publish(List.of(), new HealingConfig(),
                (url, body) -> calls.incrementAndGet());
        assertEquals(calls.get(), 0);
    }

    @Test
    public void publishPostsBuiltPayloadToConfiguredUrl() throws IOException, InterruptedException {
        System.setProperty("healing.report.webhook.enabled", "true");
        System.setProperty("healing.report.webhook.url", "https://hooks.example.com/x");
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HealingReportPublisher.publish(List.of(record("login.submitButton")), new HealingConfig(),
                (url, body) -> {
                    capturedUrl.set(url);
                    capturedBody.set(body);
                });

        assertEquals(capturedUrl.get(), "https://hooks.example.com/x");
        assertTrue(capturedBody.get().contains("login.submitButton"));
    }

    @Test
    public void publishSwallowsTransportFailure() {
        System.setProperty("healing.report.webhook.enabled", "true");
        System.setProperty("healing.report.webhook.url", "https://hooks.example.com/x");
        HealingReportPublisher.publish(List.of(record("k")), new HealingConfig(),
                (url, body) -> {
                    throw new IOException("network down");
                });
        // No exception propagated - the run must never fail because a webhook is unreachable.
    }
}
