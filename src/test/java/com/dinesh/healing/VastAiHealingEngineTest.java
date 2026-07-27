package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the full request-build + response-parse path of VastAiHealingEngine
 * (OpenAI-compatible chat-completions wire format) with a fake Transport - no
 * network, no rented GPU instance, CI-safe.
 */
public class VastAiHealingEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeMethod
    public void setUp() {
        System.setProperty("healing.llm.vastai.baseUrl", "http://203.0.113.10:8000/v1");
    }

    @AfterMethod
    public void tearDown() {
        System.clearProperty("healing.llm.vastai.baseUrl");
        System.clearProperty("healing.llm.vastai.model");
    }

    private static String apiResponse(String innerJson) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                """.formatted(innerJson.replace("\"", "\\\""));
    }

    private VastAiHealingEngine engine(VastAiHealingEngine.Transport transport) {
        return new VastAiHealingEngine(new HealingConfig(), transport, "test-key");
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
    public void proseBeforeJsonIsStillParsed() {
        var engine = engine((url, key, body) -> apiResponse(
                "Based on the page source, the best match is: "
                        + "{\"strategy\": \"accessibilityId\", \"value\": \"loginBtn\", \"confidence\": 0.8}"));

        Optional<HealingEngine.Proposal> proposal = engine.propose("k", "d", "<hierarchy/>");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ACCESSIBILITY_ID);
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
    public void httpFailureDegradesToEmpty() {
        var engine = engine((url, key, body) -> {
            throw new java.io.IOException("HTTP 502 bad gateway");
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
    public void missingBaseUrlSkipsCallEntirely() {
        System.clearProperty("healing.llm.vastai.baseUrl");
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        var engine = new VastAiHealingEngine(new HealingConfig(), (url, key, body) -> {
            called.set(true);
            return "";
        }, "test-key");

        assertTrue(engine.propose("k", "d", "<hierarchy/>").isEmpty());
        assertFalse(called.get(), "No configured base URL must mean no network call");
    }

    @Test
    public void requestBodyContainsPromptAndConfig() throws Exception {
        System.setProperty("healing.llm.vastai.model", "meta-llama/Llama-3.1-70B-Instruct");
        var engine = engine((url, key, body) -> apiResponse("{\"strategy\":\"none\"}"));
        String body = engine.buildRequestBody(
                "login.submitButton", "Primary Sign In button", "<hierarchy/>");

        JsonNode root = MAPPER.readTree(body);
        assertEquals(root.path("temperature").asInt(), 0, "Determinism requires temperature 0");
        assertEquals(root.path("model").asText(), "meta-llama/Llama-3.1-70B-Instruct");
        String prompt = root.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("login.submitButton"));
        assertTrue(prompt.contains("Primary Sign In button"));
    }
}
