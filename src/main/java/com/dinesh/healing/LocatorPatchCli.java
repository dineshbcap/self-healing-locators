package com.dinesh.healing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Jenkins/CLI entry point for Phase 4: reads a {@code healing-report.json} and a
 * {@code locators_<platform>.properties} file, and either prints a ready-to-review
 * unified diff (default) or applies the patch in place with {@code --apply}.
 *
 * <pre>
 * # Review only - prints a unified diff, touches nothing:
 * java -cp self-healing-locators.jar com.dinesh.healing.LocatorPatchCli \
 *     target/healing-report.json src/main/resources/locators/locators_android.properties android
 *
 * # Apply directly (e.g. in a "one-click PR" Jenkins job that commits the result):
 * java -cp self-healing-locators.jar com.dinesh.healing.LocatorPatchCli \
 *     target/healing-report.json src/main/resources/locators/locators_ios.properties ios --apply
 * </pre>
 *
 * Run once per platform properties file, with the matching {@code android}/{@code ios}
 * argument - locator keys are shared across both platform files by convention, so a
 * report from a mixed-platform suite naturally produces "wrong platform" warnings for
 * the other platform's keys when you run it against this one. That's expected, not an
 * error - it's exactly what stops an Android heal from being written into the iOS file.
 *
 * Not exercised by the CI-safe test suite (thin argv/stdout/exit-code glue); the
 * patch logic it calls into ({@link LocatorPatchGenerator}) is.
 */
public final class LocatorPatchCli {

    private LocatorPatchCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: LocatorPatchCli <healing-report.json> <locators.properties> "
                    + "<android|ios> [--apply] [--diff-out <file>]");
            System.exit(2);
            return;
        }

        Path reportFile = Path.of(args[0]);
        Path propertiesFile = Path.of(args[1]);
        Platform targetPlatform;
        try {
            targetPlatform = Platform.valueOf(args[2].trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("Third argument must be 'android' or 'ios', got: " + args[2]);
            System.exit(2);
            return;
        }
        boolean apply = false;
        Path diffOut = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--apply" -> apply = true;
                case "--diff-out" -> diffOut = Path.of(CliArgs.next(args, ++i, "--diff-out"));
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                    return;
                }
            }
        }

        List<HealingReporter.HealingRecord> records = readRecords(reportFile);
        if (records.isEmpty()) {
            System.out.println("No heals in " + reportFile + " - nothing to patch.");
            return;
        }

        String originalText = Files.readString(propertiesFile);
        LocatorPatchGenerator.PatchResult result =
                LocatorPatchGenerator.apply(originalText, records, targetPlatform);

        if (!result.wrongPlatformKeys().isEmpty()) {
            System.err.println("Belongs to the other platform, not applied here: " + result.wrongPlatformKeys());
        }
        if (!result.skippedKeys().isEmpty()) {
            System.err.println("Skipped (no recoverable strategy/value in report): " + result.skippedKeys());
        }
        if (!result.unmatchedKeys().isEmpty()) {
            System.err.println("Not found in " + propertiesFile + ": " + result.unmatchedKeys());
        }
        if (result.changes().isEmpty()) {
            System.out.println("No matching keys in " + propertiesFile + " - nothing to patch.");
            return;
        }

        String diff = LocatorPatchGenerator.unifiedDiff(originalText, result.changes(), propertiesFile.toString());
        if (apply) {
            Files.writeString(propertiesFile, result.patchedText());
            System.out.println("Applied " + result.changes().size() + " change(s) to " + propertiesFile);
        }
        if (diffOut != null) {
            Files.writeString(diffOut, diff);
            System.out.println("Diff written to " + diffOut);
        }
        if (!apply && diffOut == null) {
            System.out.print(diff);
        }
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
