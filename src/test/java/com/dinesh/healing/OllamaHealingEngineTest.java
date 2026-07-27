package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the full request-build + response-parse path of OllamaHealingEngine
 * with a fake Transport - no network, no running Ollama server, CI-safe.
 */
public class OllamaHealingEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String apiResponse(String innerJson) {
        return """
                {"model":"llama3.1","message":{"role":"assistant","content":"%s"},"done":true}
                """.formatted(innerJson.replace("\"", "\\\""));
    }

    private OllamaHealingEngine engine(OllamaHealingEngine.Transport transport) {
        return new OllamaHealingEngine(new HealingConfig(), transport);
    }

    @Test
    public void validProposalIsParsed() {
        var engine = engine((url, body) -> apiResponse(
                "{\"strategy\":\"id\",\"value\":\"com.td.app:id/button_login\",\"confidence\":0.94}"));

        Optional<HealingEngine.Proposal> proposal =
                engine.propose("login.submitButton", "Primary Sign In button", "<hierarchy/>");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
        assertEquals(proposal.get().value(), "com.td.app:id/button_login");
        assertEquals(proposal.get().confidence(), 0.94, 0.001);
    }

    @Test
    public void proseBeforeJsonIsStillParsed() {
        var engine = engine((url, body) -> apiResponse(
                "I'll use the id attribute here. "
                        + "{\"strategy\": \"id\", \"value\": \"loginBtn\", \"confidence\": 0.8}"));

        Optional<HealingEngine.Proposal> proposal = engine.propose("k", "d", "<hierarchy/>");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
    }

    @Test
    public void noneStrategyMeansNoProposal() {
        var engine = engine((url, body) -> apiResponse("{\"strategy\":\"none\"}"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "Element-not-present must yield empty, never a guess");
    }

    @Test
    public void indexBasedXpathIsRejected() {
        var engine = engine((url, body) -> apiResponse(
                "{\"strategy\":\"xpath\",\"value\":\"//android.widget.Button[3]\",\"confidence\":0.9}"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "Index-only xpaths are brittle and must be rejected");
    }

    @Test
    public void httpFailureDegradesToEmpty() {
        var engine = engine((url, body) -> {
            throw new java.io.IOException("connection refused - is `ollama serve` running?");
        });
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty(),
                "Server unreachable must never break the test run");
    }

    @Test
    public void garbageResponseDegradesToEmpty() {
        var engine = engine((url, body) -> apiResponse("here is your locator: btn_login"));
        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty());
    }

    @Test
    public void requestBodyContainsPromptAndUsesNoAuth() throws Exception {
        var engine = engine((url, body) -> apiResponse("{\"strategy\":\"none\"}"));
        String body = engine.buildRequestBody(
                "login.submitButton", "Primary Sign In button", "<hierarchy/>");

        JsonNode root = MAPPER.readTree(body);
        assertEquals(root.path("model").asText(), "llama3.1");
        assertEquals(root.path("options").path("temperature").asInt(), 0, "Determinism requires temperature 0");
        assertEquals(root.path("stream").asBoolean(), false);
        String prompt = root.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("login.submitButton"));
        assertTrue(prompt.contains("Primary Sign In button"));
    }
}
