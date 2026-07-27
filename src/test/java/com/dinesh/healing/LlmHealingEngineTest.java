package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the full request-build + response-parse path of LlmHealingEngine
 * with a fake Transport - no network, no API key, CI-safe.
 */
public class LlmHealingEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String apiResponse(String innerJson) {
        return """
                {"content":[{"type":"text","text":"%s"}]}
                """.formatted(innerJson.replace("\"", "\\\""));
    }

    private LlmHealingEngine engine(LlmHealingEngine.Transport transport) {
        return new LlmHealingEngine(new HealingConfig(), transport, "test-key");
    }

    @Test
    public void validProposalIsParsed() {
        var engine = engine((url, key, body) -> apiResponse(
                "{\"strategy\":\"id\",\"value\":\"com.td.app:id/button_login\",\"confidence\":0.94}"));

        Optional<HealingEngine.Proposal> proposal =
                engine.propose("login.submitButton", "Primary Sign In button", "<hierarchy/>");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
        assertEquals(proposal.get().value(), "com.td.app:id/button_login");
        assertEquals(proposal.get().confidence(), 0.94, 0.001);
    }

    @Test
    public void markdownFencedResponseIsStillParsed() {
        var engine = engine((url, key, body) -> apiResponse(
                "```json\\n{\"strategy\":\"accessibilityId\",\"value\":\"loginBtn\",\"confidence\":0.8}\\n```"));

        Optional<HealingEngine.Proposal> proposal =
                engine.propose("k", "d", "<hierarchy/>");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ACCESSIBILITY_ID);
    }

    @Test
    public void proseBeforeJsonIsStillParsed() {
        var engine = engine((url, key, body) -> apiResponse(
                "The description asks for a Placeholder Text field. I'll use an iOS Class Chain. "
                        + "{\"strategy\": \"xpath\", \"value\": \"//XCUIElementTypeTextField[@value='Placeholder text']\", \"confidence\": 0.72}"));

        Optional<HealingEngine.Proposal> proposal =
                engine.propose("k", "d", "<hierarchy/>");

        assertTrue(proposal.isPresent(), "JSON preceded by model commentary must still parse");
        assertEquals(proposal.get().strategy(), LocatorStrategy.XPATH);
        assertEquals(proposal.get().value(), "//XCUIElementTypeTextField[@value='Placeholder text']");
    }

    @Test
    public void noneStrategyMeansNoProposal() {
        var engine = engine((url, key, body) -> apiResponse("{\"strategy\":\"none\"}"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "Element-not-present must yield empty, never a guess");
    }

    @Test
    public void indexBasedXpathIsRejected() {
        var engine = engine((url, key, body) -> apiResponse(
                "{\"strategy\":\"xpath\",\"value\":\"//android.widget.Button[3]\",\"confidence\":0.9}"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "Index-only xpaths are brittle and must be rejected");
    }

    @Test
    public void attributeXpathWithIndexSuffixIsAllowed() {
        assertFalse(LlmHealingEngine.looksIndexBased(
                        "//*[contains(@resource-id,'login')]"),
                "Attribute-based xpath must pass");
        assertTrue(LlmHealingEngine.looksIndexBased("//android.widget.Button[3]"));
    }

    @Test
    public void httpFailureDegradesToEmpty() {
        var engine = engine((url, key, body) -> {
            throw new java.io.IOException("HTTP 529 overloaded");
        });
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "API failure must never break the test run");
    }

    @Test
    public void garbageResponseDegradesToEmpty() {
        var engine = engine((url, key, body) -> apiResponse("here is your locator: btn_login"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty());
    }

    @Test
    public void missingApiKeySkipsCallEntirely() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        var engine = new LlmHealingEngine(new HealingConfig(), (url, key, body) -> {
            called.set(true);
            return "";
        }, null);

        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty());
        assertFalse(called.get(), "No API key must mean no network call");
    }

    @Test
    public void requestBodyContainsPromptAndConfig() throws Exception {
        var engine = engine((url, key, body) -> apiResponse("{\"strategy\":\"none\"}"));
        String body = engine.buildRequestBody(
                "login.submitButton", "Primary Sign In button", "<hierarchy/>");

        JsonNode root = MAPPER.readTree(body);
        assertEquals(root.path("temperature").asInt(), 0, "Determinism requires temperature 0");
        assertTrue(root.path("model").asText().startsWith("claude-"));
        String prompt = root.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("login.submitButton"));
        assertTrue(prompt.contains("Primary Sign In button"));
        assertTrue(prompt.contains("NEVER return an index-based xpath"));
    }
}
