package com.dinesh.healing;

import org.openqa.selenium.By;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
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
        assertEquals(DeterministicHealer.distinctiveFragment("btn_1"), "btn",
                "With the current 3-char minimum, 'btn' itself now qualifies as distinctive");
        assertNull(DeterministicHealer.distinctiveFragment("id_1"),
                "Both fragments ('id', '1') are still too short to be distinctive");
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
                .anyMatch(c -> c.by().toString().contains("translate(@resource-id")
                        && c.by().toString().contains("'login'")),
                "Resource-id fragment match must be case-insensitive via translate()");
        assertTrue(candidates.stream()
                .anyMatch(c -> c.by().toString().contains("textMatches") && c.by().toString().contains("(?i)")),
                "Text match must be case-insensitive");
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
    public void androidResourceIdContainsA11yValueIsCaseInsensitive() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ACCESSIBILITY_ID, "LoginButton");
        var candidates = DeterministicHealer.buildCandidates(Platform.ANDROID, def);
        var candidate = candidates.stream()
                .filter(c -> c.name().equals("resourceIdContainsA11yValue"))
                .findFirst().orElseThrow();
        assertTrue(candidate.by().toString().contains("translate(@resource-id"));
        assertTrue(candidate.by().toString().contains("'loginbutton'"),
                "Search term must be lowercased to match the lowercased attribute");
    }

    @Test
    public void iosNameContainsFragmentIsCaseInsensitive() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ID, "com.td.app:id/btn_login");
        var candidates = DeterministicHealer.buildCandidates(Platform.IOS, def);
        var candidate = candidates.stream()
                .filter(c -> c.name().equals("iosNameContainsFragment"))
                .findFirst().orElseThrow();
        assertTrue(candidate.by().toString().contains("CONTAINS[c]"));
    }

    @Test
    public void androidDescContainsKeywordIsCaseInsensitive() {
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ID, "com.td.app:id/btn_login");
        var candidates = DeterministicHealer.buildCandidates(Platform.ANDROID, def);
        var candidate = candidates.stream()
                .filter(c -> c.name().startsWith("androidDescContains"))
                .findFirst().orElseThrow();
        assertTrue(candidate.by().toString().contains("translate(@content-desc"));
    }

    @Test
    public void exactMatchCrossSwapCandidatesRemainCaseSensitive() {
        // a11yIdFromIdToken and the iOS label/value == candidates are genuine exact
        // matches, not contains-based - left case-sensitive on purpose (see class docs).
        LocatorDef androidDef = new LocatorDef("k", "d", LocatorStrategy.ID, "btn_login");
        var androidCandidates = DeterministicHealer.buildCandidates(Platform.ANDROID, androidDef);
        var a11yCandidate = androidCandidates.stream()
                .filter(c -> c.name().equals("a11yIdFromIdToken"))
                .findFirst().orElseThrow();
        assertTrue(a11yCandidate.by().toString().equals("AppiumBy.accessibilityId: btn_login"));

        LocatorDef iosDef = new LocatorDef("k", "d", LocatorStrategy.ACCESSIBILITY_ID, "loginButton");
        var iosCandidates = DeterministicHealer.buildCandidates(Platform.IOS, iosDef);
        var labelCandidate = iosCandidates.stream()
                .filter(c -> c.name().equals("iosLabelFromA11yId"))
                .findFirst().orElseThrow();
        assertTrue(labelCandidate.by().toString().contains("label == 'loginButton'"),
                "Exact-match candidates use == , not a case-insensitive contains");
    }

    @Test
    public void generatedCaseInsensitiveXpathActuallyMatchesADifferentlyCasedAttribute() throws Exception {
        // Proves the translate() expression is real, valid XPath 1.0 that matches -
        // not just that the string happens to contain the right substrings.
        LocatorDef def = new LocatorDef("login.submitButton",
                "Primary Sign In button on the login screen",
                LocatorStrategy.ID, "com.td.app:id/btn_login");
        String xpath = (String) ((By.Remotable) DeterministicHealer.buildCandidates(Platform.ANDROID, def).stream()
                .filter(c -> c.name().equals("resourceIdContainsFragment"))
                .findFirst().orElseThrow()
                .by()).getRemoteParameters().value();

        String xml = "<hierarchy><node resource-id=\"com.td.app:id/BTN_LOGIN\"/></hierarchy>";
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        NodeList matches = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(xpath, doc, XPathConstants.NODESET);

        assertEquals(matches.getLength(), 1,
                "xpath must match an all-caps resource-id via the lowercase search term 'login'");
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
