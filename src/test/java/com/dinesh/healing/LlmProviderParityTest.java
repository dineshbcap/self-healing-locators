package com.dinesh.healing;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;

/**
 * Guards against provider drift: {@link LlmHealingEngine}, {@link OllamaHealingEngine}
 * and {@link VastAiHealingEngine} differ only in how they unwrap their HTTP
 * envelope before handing the assistant's raw text to the shared
 * {@link LlmResponseParser#parseProposal}. Feeding the exact same assistant
 * text through all three (each wrapped in that provider's real envelope shape)
 * must yield the same {@link HealingEngine.Proposal} - if one provider's
 * envelope-unwrap or a future parser tweak silently changes behavior for only
 * one provider, this fails.
 */
public class LlmProviderParityTest {

    @BeforeMethod
    public void setUp() {
        System.setProperty("healing.llm.vastai.baseUrl", "http://203.0.113.10:8000/v1");
    }

    @AfterMethod
    public void tearDown() {
        System.clearProperty("healing.llm.vastai.baseUrl");
    }

    @DataProvider
    public Object[][] assistantTexts() {
        return new Object[][] {
                {"{\"strategy\":\"id\",\"value\":\"com.td.app:id/button_login\",\"confidence\":0.94}"},
                {"```json\n{\"strategy\":\"accessibilityId\",\"value\":\"loginBtn\",\"confidence\":0.8}\n```"},
                {"I'll use the id attribute here. "
                        + "{\"strategy\": \"id\", \"value\": \"loginBtn\", \"confidence\": 0.8}"},
                {"{\"strategy\":\"none\"}"},
                {"no json anywhere in this reply"},
        };
    }

    @Test(dataProvider = "assistantTexts")
    public void allProvidersParseIdenticalAssistantTextTheSame(String assistantText) {
        Optional<HealingEngine.Proposal> anthropic =
                anthropicEngine(assistantText).propose("k", "d", "<hierarchy/>");
        Optional<HealingEngine.Proposal> ollama =
                ollamaEngine(assistantText).propose("k", "d", "<hierarchy/>");
        Optional<HealingEngine.Proposal> vastAi =
                vastAiEngine(assistantText).propose("k", "d", "<hierarchy/>");

        assertEquals(ollama, anthropic, "Ollama diverged from Anthropic for: " + assistantText);
        assertEquals(vastAi, anthropic, "vast.ai diverged from Anthropic for: " + assistantText);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private LlmHealingEngine anthropicEngine(String assistantText) {
        String body = """
                {"content":[{"type":"text","text":"%s"}]}
                """.formatted(jsonEscape(assistantText));
        return new LlmHealingEngine(new HealingConfig(), (url, key, b) -> body, "test-key");
    }

    private OllamaHealingEngine ollamaEngine(String assistantText) {
        String body = """
                {"model":"llama3.1","message":{"role":"assistant","content":"%s"},"done":true}
                """.formatted(jsonEscape(assistantText));
        return new OllamaHealingEngine(new HealingConfig(), (url, key, b) -> body);
    }

    private VastAiHealingEngine vastAiEngine(String assistantText) {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                """.formatted(jsonEscape(assistantText));
        return new VastAiHealingEngine(new HealingConfig(), (url, key, b) -> body, "test-key");
    }
}
