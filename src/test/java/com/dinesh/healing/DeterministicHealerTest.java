package com.dinesh.healing;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DeterministicHealerTest {

    @Test
    public void lastIdTokenStripsResourceIdPrefix() {
        LocatorDef def = new LocatorDef("k", "d",
                LocatorStrategy.ID, "com.td.app:id/btn_login");
        assertEquals(DeterministicHealer.lastIdToken(def), "btn_login");
    }

    @Test
    public void lastIdTokenIsNullForXpath() {
        LocatorDef def = new LocatorDef("k", "d",
                LocatorStrategy.XPATH, "//android.widget.Button");
        assertNull(DeterministicHealer.lastIdToken(def));
    }

    @Test
    public void distinctiveFragmentSkipsGenericPrefixes() {
        assertEquals(DeterministicHealer.distinctiveFragment("btn_login"), "login");
        assertEquals(DeterministicHealer.distinctiveFragment("username_input"), "username");
        assertNull(DeterministicHealer.distinctiveFragment("btn_1"),
                "Nothing distinctive should remain");
    }

    @Test
    public void descriptionKeywordsSkipStopWordsAndCapAtThree() {
        List<String> kws = DeterministicHealer.descriptionKeywords(
                "Primary Sign In button on the login screen for customers");
        assertTrue(kws.contains("Sign"));
        assertTrue(kws.contains("login"));
        assertEquals(kws.size(), 3);
    }

    @Test
    public void androidCandidatesIncludeFragmentAndTextStrategies() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ID, "com.td.app:id/btn_login");
        var candidates = DeterministicHealer.buildCandidates(Platform.ANDROID, def);
        assertTrue(candidates.stream()
                .anyMatch(c -> c.by().toString().contains("resource-id,'login'")));
        assertTrue(candidates.stream()
                .anyMatch(c -> c.by().toString().contains("textContains")));
    }

    @Test
    public void iosCandidatesUsePredicates() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ACCESSIBILITY_ID, "loginButton");
        var candidates = DeterministicHealer.buildCandidates(Platform.IOS, def);
        assertTrue(candidates.stream()
                .anyMatch(c -> c.by().toString().contains("CONTAINS")));
    }

    @Test
    public void iosCandidatesTryLabelBeforeValueFromAccessibilityId() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ACCESSIBILITY_ID, "loginButton");
        var candidates = DeterministicHealer.buildCandidates(Platform.IOS, def);

        int labelIdx = indexOfCandidate(candidates, "iosLabelFromA11yId");
        int valueIdx = indexOfCandidate(candidates, "iosValueFromA11yId");

        assertTrue(labelIdx >= 0 && valueIdx >= 0, "Both label and value candidates should be present");
        assertTrue(labelIdx < valueIdx, "label must be tried before value");
        assertEquals(candidates.get(labelIdx).by().toString().contains("label == 'loginButton'"), true);
        assertEquals(candidates.get(valueIdx).by().toString().contains("value == 'loginButton'"), true);
    }

    @Test
    public void androidGetsNoLabelOrValueFallback() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ACCESSIBILITY_ID, "loginButton");
        var candidates = DeterministicHealer.buildCandidates(Platform.ANDROID, def);
        assertTrue(candidates.stream()
                .noneMatch(c -> c.name().equals("iosLabelFromA11yId") || c.name().equals("iosValueFromA11yId")));
    }

    private static int indexOfCandidate(List<DeterministicHealer.Candidate> candidates, String name) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
