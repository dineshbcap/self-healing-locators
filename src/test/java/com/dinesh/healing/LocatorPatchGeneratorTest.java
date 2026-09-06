package com.dinesh.healing;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LocatorPatchGeneratorTest {

    private static final String SAMPLE_PROPERTIES = String.join("\n",
            "# Android locator definitions.",
            "login.usernameField.description=Username entry field on the login screen",
            "login.usernameField=id=com.td.app:id/username",
            "",
            "login.submitButton.description=Primary Sign In button on the login screen",
            "login.submitButton=id=com.td.app:id/btn_login",
            "",
            "accounts.transferButton.description=Transfer money button",
            "accounts.transferButton=id=com.td.app:id/btn_transfer",
            "");

    private static HealingReporter.HealingRecord record(String key, String strategy, String value) {
        return record(key, "android", strategy, value);
    }

    private static HealingReporter.HealingRecord record(String key, String platform, String strategy, String value) {
        return new HealingReporter.HealingRecord(
                "2026-01-01T00:00:00Z", key, platform, "desc", "id=old", "By.xpath: irrelevant-display-string",
                strategy, value, "resourceIdContainsFragment", "deterministic");
    }

    @Test
    public void patchesTheMatchedKeyLineOnly() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("login.submitButton", "xpath", "//*[contains(@resource-id,'login')]")),
                Platform.ANDROID);

        assertEquals(result.changes().size(), 1);
        assertTrue(result.patchedText().contains(
                "login.submitButton=xpath=//*[contains(@resource-id,'login')]"));
        assertTrue(result.unmatchedKeys().isEmpty());
        assertTrue(result.skippedKeys().isEmpty());
        assertTrue(result.wrongPlatformKeys().isEmpty());
    }

    @Test
    public void neverTouchesTheDescriptionLineForTheSameKey() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("login.submitButton", "xpath", "//new")), Platform.ANDROID);

        assertTrue(result.patchedText().contains(
                "login.submitButton.description=Primary Sign In button on the login screen"),
                "Description line must survive untouched");
    }

    @Test
    public void otherLinesSurviveByteForByteUnchanged() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("login.submitButton", "xpath", "//new")), Platform.ANDROID);

        assertTrue(result.patchedText().contains("login.usernameField=id=com.td.app:id/username"));
        assertTrue(result.patchedText().contains("accounts.transferButton=id=com.td.app:id/btn_transfer"));
        assertTrue(result.patchedText().contains("# Android locator definitions."));
    }

    @Test
    public void patchesMultipleKeysInOnePass() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES, List.of(
                record("login.submitButton", "xpath", "//new_submit"),
                record("accounts.transferButton", "accessibilityId", "transferMoney")), Platform.ANDROID);

        assertEquals(result.changes().size(), 2);
        assertTrue(result.patchedText().contains("login.submitButton=xpath=//new_submit"));
        assertTrue(result.patchedText().contains("accounts.transferButton=accessibilityId=transferMoney"));
    }

    @Test
    public void keyMissingFromFileIsReportedAsUnmatched() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("android.only.key", "accessibilityId", "someValue")), Platform.ANDROID);

        assertEquals(result.changes().size(), 0);
        assertEquals(result.unmatchedKeys(), List.of("android.only.key"));
        assertEquals(result.patchedText(), SAMPLE_PROPERTIES, "File must be untouched when nothing matches");
    }

    @Test
    public void recordWithoutStrategyOrValueIsSkippedNotGuessed() {
        HealingReporter.HealingRecord blank = new HealingReporter.HealingRecord(
                "2026-01-01T00:00:00Z", "login.submitButton", "android", "desc", "id=old", "By.xpath: irrelevant",
                "", "", "llm", "llm");

        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES, List.of(blank), Platform.ANDROID);

        assertEquals(result.changes().size(), 0);
        assertEquals(result.skippedKeys(), List.of("login.submitButton"));
        assertEquals(result.patchedText(), SAMPLE_PROPERTIES);
    }

    @Test
    public void recordFromTheOtherPlatformIsNeverAppliedHere() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("login.submitButton", "ios", "accessibilityId", "loginButton")), Platform.ANDROID);

        assertEquals(result.changes().size(), 0);
        assertEquals(result.wrongPlatformKeys(), List.of("login.submitButton"));
        assertEquals(result.patchedText(), SAMPLE_PROPERTIES,
                "An iOS heal must never overwrite the Android file's line for the same key");
    }

    @Test
    public void recordWithNoPlatformFieldIsLetThroughForBackwardCompatibility() {
        // Simulates a report written before the platform field existed.
        HealingReporter.HealingRecord noPlatform = new HealingReporter.HealingRecord(
                "2026-01-01T00:00:00Z", "login.submitButton", "", "desc", "id=old", "By.xpath: irrelevant",
                "xpath", "//new", "resourceIdContainsFragment", "deterministic");

        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES, List.of(noPlatform), Platform.ANDROID);

        assertEquals(result.changes().size(), 1);
        assertTrue(result.patchedText().contains("login.submitButton=xpath=//new"));
    }

    @Test
    public void lastRecordWinsWhenTheSameKeyHealsTwiceInOneReport() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES, List.of(
                record("login.submitButton", "xpath", "//first-attempt"),
                record("login.submitButton", "xpath", "//final-state")), Platform.ANDROID);

        assertEquals(result.changes().size(), 1);
        assertTrue(result.patchedText().contains("login.submitButton=xpath=//final-state"));
        assertFalse(result.patchedText().contains("//first-attempt"));
    }

    @Test
    public void unifiedDiffIsEmptyWhenNoChanges() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES, List.of(), Platform.ANDROID);
        String diff = LocatorPatchGenerator.unifiedDiff(SAMPLE_PROPERTIES, result.changes(), "locators.properties");
        assertEquals(diff, "");
    }

    @Test
    public void unifiedDiffHasGitApplyCompatibleHeaderAndHunk() {
        var result = LocatorPatchGenerator.apply(SAMPLE_PROPERTIES,
                List.of(record("login.submitButton", "xpath", "//new")), Platform.ANDROID);
        String diff = LocatorPatchGenerator.unifiedDiff(SAMPLE_PROPERTIES, result.changes(), "locators.properties");

        assertTrue(diff.startsWith("--- a/locators.properties\n+++ b/locators.properties\n"));
        assertTrue(diff.contains("@@ -"));
        assertTrue(diff.contains("-login.submitButton=id=com.td.app:id/btn_login\n"));
        assertTrue(diff.contains("+login.submitButton=xpath=//new\n"));
    }

    @Test
    public void nearbyChangesMergeIntoOneHunkFarApartChangesGetSeparateHunks() {
        // key.a at line 1, key.b at line 3 (gap 2, within 2*context=6 -> must merge),
        // key.c at line 50 (gap ~46 from key.b -> must be its own hunk).
        StringBuilder sb = new StringBuilder();
        sb.append("key.a=id=old_a\n");
        sb.append("filler=id=x\n");
        sb.append("key.b=id=old_b\n");
        for (int i = 0; i < 45; i++) {
            sb.append("filler").append(i).append("=id=x\n");
        }
        sb.append("key.c=id=old_c\n");
        String text = sb.toString();

        var result = LocatorPatchGenerator.apply(text, List.of(
                record("key.a", "xpath", "//a"),
                record("key.b", "xpath", "//b"),
                record("key.c", "xpath", "//c")), Platform.ANDROID);
        assertEquals(result.changes().size(), 3);

        String diff = LocatorPatchGenerator.unifiedDiff(text, result.changes(), "locators.properties");
        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(hunkCount, 2, "key.a+key.b must merge into one hunk, key.c must be a separate hunk:\n" + diff);
    }
}
