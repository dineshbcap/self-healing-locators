package com.dinesh.healing;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HealingChurnAnalyzerTest {

    private static final String HEADER = "timestamp,buildId,platform,locatorKey,healingStrategy\n";

    @Test
    public void groupsHealsByScreenSegmentOfTheLocatorKey() {
        String csv = HEADER
                + "2026-01-01T00:00:00Z,build-1,android,login.submitButton,resourceIdContainsFragment\n"
                + "2026-01-01T00:00:00Z,build-1,android,login.usernameField,resourceIdContainsFragment\n"
                + "2026-01-01T00:00:00Z,build-1,android,accounts.transferButton,resourceIdContainsFragment\n";

        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);

        assertEquals(churns.size(), 2, "Two distinct screens: login, accounts");
        HealingChurnAnalyzer.ScreenChurn login = churns.stream()
                .filter(c -> c.screen().equals("login")).findFirst().orElseThrow();
        assertEquals(login.totalHeals(), 2);
        assertTrue(login.locatorKeys().containsAll(List.of("login.submitButton", "login.usernameField")));
    }

    @Test
    public void distinctBuildsCountsUniqueBuildsNotTotalEvents() {
        // Same build healing the same locator 3 times (a flaky run) vs a genuinely
        // recurring cross-build issue must be distinguishable.
        String csv = HEADER
                + "2026-01-01T00:00:00Z,build-1,android,login.submitButton,x\n"
                + "2026-01-01T00:05:00Z,build-1,android,login.submitButton,x\n"
                + "2026-01-01T00:10:00Z,build-1,android,login.submitButton,x\n";

        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);

        assertEquals(churns.get(0).totalHeals(), 3);
        assertEquals(churns.get(0).distinctBuilds(), 1);
    }

    @Test
    public void screensAreRankedByDistinctBuildsFirstThenTotalHeals() {
        String csv = HEADER
                // "rare" heals 5 times but only ever in one build - a single flaky run.
                + "2026-01-01T00:00:00Z,build-1,android,rare.button,x\n"
                + "2026-01-01T00:01:00Z,build-1,android,rare.button,x\n"
                + "2026-01-01T00:02:00Z,build-1,android,rare.button,x\n"
                + "2026-01-01T00:03:00Z,build-1,android,rare.button,x\n"
                + "2026-01-01T00:04:00Z,build-1,android,rare.button,x\n"
                // "chronic" heals only twice, but across two separate builds - real churn.
                + "2026-01-02T00:00:00Z,build-2,android,chronic.button,x\n"
                + "2026-01-03T00:00:00Z,build-3,android,chronic.button,x\n";

        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);

        assertEquals(churns.get(0).screen(), "chronic",
                "Cross-build recurrence must outrank a single flaky run with more raw heals");
        assertEquals(churns.get(1).screen(), "rare");
    }

    @Test
    public void keyWithNoDotIsItsOwnScreen() {
        assertEquals(HealingChurnAnalyzer.screenOf("standaloneKey"), "standaloneKey");
        assertEquals(HealingChurnAnalyzer.screenOf("login.submitButton"), "login");
    }

    @Test
    public void malformedAndBlankLinesAreSkippedNotFatal() {
        String csv = HEADER
                + "\n"
                + "not,enough,cols\n"
                + "2026-01-01T00:00:00Z,build-1,android,login.submitButton,x\n";

        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);

        assertEquals(churns.size(), 1);
        assertEquals(churns.get(0).totalHeals(), 1);
    }

    @Test
    public void emptyInputYieldsEmptyList() {
        assertTrue(HealingChurnAnalyzer.analyze("").isEmpty());
        assertTrue(HealingChurnAnalyzer.analyze(HEADER).isEmpty());
    }

    @Test
    public void renderReportHandlesEmptyChurnList() {
        String report = HealingChurnAnalyzer.renderReport(List.of(), 10);
        assertEquals(report, "No healing events recorded - nothing to analyze.");
    }

    @Test
    public void renderReportTruncatesToTopNAndCountsTheRest() {
        String csv = HEADER
                + "2026-01-01T00:00:00Z,build-1,android,a.key,x\n"
                + "2026-01-01T00:00:00Z,build-1,android,b.key,x\n"
                + "2026-01-01T00:00:00Z,build-1,android,c.key,x\n";
        List<HealingChurnAnalyzer.ScreenChurn> churns = HealingChurnAnalyzer.analyze(csv);

        String report = HealingChurnAnalyzer.renderReport(churns, 2);

        assertTrue(report.contains("top 2 of 3"));
        assertTrue(report.contains("...and 1 more screen(s)"));
    }
}
