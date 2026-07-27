package com.dinesh.healing;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Verifies healing.llm.provider selects the right HealingEngine implementation. */
public class LlmHealingEngineFactoryTest {

    @BeforeMethod
    public void setUp() {
        System.clearProperty("healing.llm.enabled");
        System.clearProperty("healing.llm.provider");
    }

    @AfterMethod
    public void tearDown() {
        System.clearProperty("healing.llm.enabled");
        System.clearProperty("healing.llm.provider");
    }

    @Test
    public void disabledReturnsNoOp() {
        System.setProperty("healing.llm.enabled", "false");
        assertSame(LlmHealingEngineFactory.create(new HealingConfig()), HealingEngine.NO_OP);
    }

    @Test
    public void defaultProviderIsAnthropic() {
        System.setProperty("healing.llm.enabled", "true");
        assertTrue(LlmHealingEngineFactory.create(new HealingConfig()) instanceof LlmHealingEngine);
    }

    @Test
    public void ollamaProviderSelected() {
        System.setProperty("healing.llm.enabled", "true");
        System.setProperty("healing.llm.provider", "ollama");
        assertTrue(LlmHealingEngineFactory.create(new HealingConfig()) instanceof OllamaHealingEngine);
    }

    @Test
    public void vastaiProviderSelected() {
        System.setProperty("healing.llm.enabled", "true");
        System.setProperty("healing.llm.provider", "vastai");
        assertTrue(LlmHealingEngineFactory.create(new HealingConfig()) instanceof VastAiHealingEngine);
    }

    @Test
    public void providerIsCaseInsensitive() {
        System.setProperty("healing.llm.enabled", "true");
        System.setProperty("healing.llm.provider", "OLLAMA");
        assertTrue(LlmHealingEngineFactory.create(new HealingConfig()) instanceof OllamaHealingEngine);
    }

    @Test
    public void unknownProviderFallsBackToNoOp() {
        System.setProperty("healing.llm.enabled", "true");
        System.setProperty("healing.llm.provider", "bogus");
        assertSame(LlmHealingEngineFactory.create(new HealingConfig()), HealingEngine.NO_OP);
    }
}
