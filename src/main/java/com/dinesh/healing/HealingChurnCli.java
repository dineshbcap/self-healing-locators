package com.dinesh.healing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Jenkins/CLI entry point for Phase 5: reads the accumulated
 * {@code healing-events.csv} (see {@link HealingReporter#appendEvents}) and prints a
 * screen-churn ranking.
 *
 * <pre>
 * java -cp self-healing-locators.jar com.dinesh.healing.HealingChurnCli \
 *     target/healing-events.csv --top 10
 * </pre>
 *
 * Only useful once the events file has accumulated heals across more than one build -
 * see the cross-build persistence note on {@link HealingConfig#eventsFile()}. Not
 * exercised by the CI-safe test suite (thin argv/stdout glue); the analysis logic it
 * calls into ({@link HealingChurnAnalyzer}) is.
 */
public final class HealingChurnCli {

    private HealingChurnCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: HealingChurnCli <healing-events.csv> [--top N]");
            System.exit(2);
            return;
        }

        Path eventsFile = Path.of(args[0]);
        int topN = 10;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--top" -> topN = Integer.parseInt(CliArgs.next(args, ++i, "--top"));
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                    return;
                }
            }
        }

        if (Files.notExists(eventsFile)) {
            System.out.println("No events file at " + eventsFile + " - nothing to analyze yet.");
            return;
        }

        String csv = Files.readString(eventsFile);
        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);
        System.out.println(HealingChurnAnalyzer.renderReport(churns, topN));
    }
}
