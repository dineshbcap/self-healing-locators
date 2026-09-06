package com.dinesh.healing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Posts a "N locators healed - POM updates recommended" summary to a Slack or
 * Teams webhook at suite end, so the healing signal reaches the team without
 * anyone opening the JSON report. Call from the same {@code @AfterSuite} hook
 * that calls {@link HealingReporter#writeReport}:
 *
 * <pre>
 * HealingReportPublisher.publish(HealingReporter.records(), config);
 * </pre>
 *
 * No-ops (never throws, never fails the build) when the webhook is disabled,
 * the URL is blank, there are no heals to report, or the HTTP call fails -
 * exactly the same "never break the run" contract as the LLM engines.
 *
 * The HTTP layer is injectable ({@link Transport}) so unit tests exercise
 * payload building and the publish/no-op decision without network access.
 */
public final class HealingReportPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(HealingReportPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Injectable HTTP seam for tests. */
    @FunctionalInterface
    public interface Transport {
        void post(String url, String jsonBody) throws IOException, InterruptedException;
    }

    private HealingReportPublisher() {
    }

    public static void publish(List<HealingReporter.HealingRecord> records, HealingConfig config) {
        publish(records, config, defaultTransport(config));
    }

    static void publish(List<HealingReporter.HealingRecord> records, HealingConfig config, Transport transport) {
        if (!config.reportWebhookEnabled()) {
            return;
        }
        if (config.reportWebhookUrl().isBlank()) {
            LOG.warn("Healing report webhook is enabled but healing.report.webhook.url is blank - skipping");
            return;
        }
        if (records == null || records.isEmpty()) {
            LOG.debug("No heals this run - skipping webhook notification");
            return;
        }
        try {
            String payload = buildPayload(records, config.reportWebhookFormat(), config.reportWebhookMaxItems());
            transport.post(config.reportWebhookUrl(), payload);
            LOG.info("Posted healing summary ({} heal(s)) to webhook", records.size());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not post healing summary to webhook: {}", e.toString());
        } catch (RuntimeException e) {
            LOG.warn("Could not build/post healing summary: {}", e.toString());
        }
    }

    /** Plain-text summary shared by every payload format. */
    static String summaryText(List<HealingReporter.HealingRecord> records, int maxItems) {
        StringBuilder sb = new StringBuilder()
                .append(records.size())
                .append(records.size() == 1 ? " locator healed" : " locators healed")
                .append(" - POM updates recommended:\n");
        int shown = Math.min(records.size(), Math.max(maxItems, 0));
        for (int i = 0; i < shown; i++) {
            HealingReporter.HealingRecord r = records.get(i);
            sb.append("- ").append(r.locatorKey()).append(": ")
                    .append(r.originalLocator()).append(" -> ").append(r.healedLocator())
                    .append(" (via ").append(r.healingStrategy()).append(")\n");
        }
        if (records.size() > shown) {
            sb.append("...and ").append(records.size() - shown).append(" more\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Builds the webhook JSON body for the given format:
     *   - "slack"            Slack incoming-webhook: {"text": "..."}
     *   - "teams"             Teams Power Automate workflow-webhook trigger: {"text": "..."}
     *                         (the current supported integration path - point the workflow's
     *                         Adaptive Card at the "text" field; the exact schema is whatever
     *                         your Flow expects, so verify with a test payload)
     *   - "teams-messagecard" legacy Office 365 Connector MessageCard - Microsoft has been
     *                         retiring these since 2024/2025; only use if your tenant still
     *                         has a working connector
     * Falls back to the "slack" shape for an unrecognized format rather than failing.
     */
    static String buildPayload(List<HealingReporter.HealingRecord> records, String format, int maxItems) {
        String text = summaryText(records, maxItems);
        ObjectNode root = MAPPER.createObjectNode();
        if ("teams-messagecard".equals(format)) {
            root.put("@type", "MessageCard");
            root.put("@context", "http://schema.org/extensions");
            root.put("summary", "Self-healing locators");
            root.put("title", "Self-healing locators");
            root.put("text", text);
        } else {
            // "slack" and "teams" (workflow-webhook) both take a plain {"text": ...} body.
            root.put("text", text);
        }
        return root.toString();
    }

    private static Transport defaultTransport(HealingConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration timeout = Duration.ofSeconds(config.reportWebhookTimeoutSeconds());
        return (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Webhook returned HTTP " + response.statusCode()
                        + ": " + truncate(response.body(), 500));
            }
        };
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
