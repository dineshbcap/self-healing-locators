package com.dinesh.healing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Jenkins post-build entry point: reads a {@code healing-report.json} written
 * by {@link HealingReporter#writeReport} and either prints the Slack/Teams
 * webhook payload to stdout (pipe into {@code curl}) or posts it directly.
 *
 * <pre>
 * # Print the payload (let the pipeline own the curl call / credential):
 * java -cp self-healing-locators.jar com.dinesh.healing.HealingReportPublisherCli \
 *     target/healing-report.json --format slack
 *
 * # Or post directly:
 * java -cp self-healing-locators.jar com.dinesh.healing.HealingReportPublisherCli \
 *     target/healing-report.json --format teams --post "$WEBHOOK_URL"
 * </pre>
 *
 * Exits 0 with no output when the report has zero heals - nothing to post.
 * Not exercised by the CI-safe test suite (thin argv/stdout/exit-code glue);
 * the payload logic it calls into ({@link HealingReportPublisher}) is.
 */
public final class HealingReportPublisherCli {

    private HealingReportPublisherCli() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1) {
            System.err.println("Usage: HealingReportPublisherCli <healing-report.json> "
                    + "[--format slack|teams|teams-messagecard] [--max-items N] [--post <webhookUrl>]");
            System.exit(2);
            return;
        }

        Path reportFile = Path.of(args[0]);
        String format = "slack";
        String webhookUrl = null;
        int maxItems = 20;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--format" -> format = args[++i];
                case "--max-items" -> maxItems = Integer.parseInt(args[++i]);
                case "--post" -> webhookUrl = args[++i];
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                    return;
                }
            }
        }

        List<HealingReporter.HealingRecord> records = readRecords(reportFile);
        if (records.isEmpty()) {
            System.out.println("No heals in " + reportFile + " - nothing to publish.");
            return;
        }

        String payload = HealingReportPublisher.buildPayload(records, format, maxItems);
        if (webhookUrl == null) {
            System.out.println(payload);
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            System.err.println("Webhook returned HTTP " + response.statusCode() + ": " + response.body());
            System.exit(1);
            return;
        }
        System.out.println("Posted " + records.size() + " heal(s) to webhook.");
    }

    private static List<HealingReporter.HealingRecord> readRecords(Path file) throws IOException {
        if (Files.notExists(file)) {
            return List.of();
        }
        String json = Files.readString(file);
        if (json.isBlank()) {
            return List.of();
        }
        ObjectMapper mapper = new ObjectMapper();
        HealingReporter.HealingRecord[] records = mapper.readValue(json, HealingReporter.HealingRecord[].class);
        return Arrays.asList(records);
    }
}
