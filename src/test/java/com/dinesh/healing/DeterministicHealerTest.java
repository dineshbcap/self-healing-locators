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
}
