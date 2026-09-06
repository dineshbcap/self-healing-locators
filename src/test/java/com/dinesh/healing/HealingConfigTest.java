package com.dinesh.healing;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Every phase of this project reads its behavior through {@link HealingConfig}, so its
 * three-way precedence (system property > classpath properties file > hardcoded default)
 * and every getter's documented default deserve direct coverage, not just incidental
 * exercise through other classes' tests.
 */
public class HealingConfigTest {

    @AfterMethod
    public void clearSystemProperties() {
        System.clearProperty("healing.enabled");
        System.clearProperty("healing.llm.provider");
        System.clearProperty("healing.report.webhook.format");
    }

    /** A classpath resource that doesn't exist - isolates every getter's hardcoded default. */
    private static HealingConfig noResource() {
        return new HealingConfig("does-not-exist.properties");
    }

    @Test
    public void everyGetterFallsBackToItsDocumentedDefault() {
        HealingConfig config = noResource();

        assertTrue(config.enabled());
        assertFalse(config.failOnHeal());
        assertEquals(config.cacheFile(), "target/healing-cache.json");
        assertEquals(config.reportFile(), "target/healing-report.json");
        assertEquals(config.metricsFile(), "target/healing-metrics.csv");
        assertEquals(config.eventsFile(), "target/healing-events.csv");

        assertFalse(config.reportWebhookEnabled());
        assertEquals(config.reportWebhookUrl(), "");
        assertEquals(config.reportWebhookFormat(), "slack");
        assertEquals(config.reportWebhookTimeoutSeconds(), 10);
        assertEquals(config.reportWebhookMaxItems(), 20);

        assertFalse(config.llmEnabled());
        assertEquals(config.llmProvider(), "anthropic");
        assertEquals(config.llmConfidenceThreshold(), 0.7, 0.0001);
        assertEquals(config.llmTimeoutSeconds(), 30);
        assertEquals(config.llmMaxPageSourceChars(), 60000);
        assertEquals(config.llmModel(), "claude-sonnet-4-6");
        assertEquals(config.llmApiKeyEnv(), "ANTHROPIC_API_KEY");

        assertEquals(config.ollamaBaseUrl(), "http://localhost:11434");
        assertEquals(config.ollamaModel(), "llama3.1");
        assertEquals(config.ollamaNumCtx(), 8192);
        assertEquals(config.ollamaApiKeyEnv(), "");

        assertEquals(config.vastAiBaseUrl(), "");
        assertEquals(config.vastAiModel(), "");
        assertEquals(config.vastAiApiKeyEnv(), "VASTAI_API_KEY");
    }

    @Test
    public void missingClasspathResourceDoesNotThrow() {
        // HealingConfig(String) silently falls through to defaults + system properties
        // when the named resource isn't found - a typo'd resource name must never crash
        // the whole suite at driver-factory bootstrap.
        HealingConfig config = noResource();
        assertTrue(config.enabled(), "Missing resource must still yield hardcoded defaults");
    }

    @Test
    public void classpathPropertiesFileValueIsUsedWhenNoSystemPropertyOverrides() {
        HealingConfig config = new HealingConfig("healing-test-custom.properties");

        assertFalse(config.enabled(), "healing-test-custom.properties sets healing.enabled=false");
        assertEquals(config.llmProvider(), "ollama");
    }

    @Test
    public void systemPropertyOverridesClasspathPropertiesFileValue() {
        // healing-test-custom.properties sets healing.enabled=false; a system property
        // must win over it.
        System.setProperty("healing.enabled", "true");
        HealingConfig config = new HealingConfig("healing-test-custom.properties");

        assertTrue(config.enabled(), "System property must override the classpath file's value");
    }

    @Test
    public void systemPropertyOverridesHardcodedDefault() {
        System.setProperty("healing.llm.provider", "vastai");
        HealingConfig config = noResource();

        assertEquals(config.llmProvider(), "vastai");
    }

    @Test
    public void providerAndFormatGettersAreTrimmedAndLowercased() {
        System.setProperty("healing.llm.provider", "  VastAI  ");
        System.setProperty("healing.report.webhook.format", "  Teams  ");
        HealingConfig config = noResource();

        assertEquals(config.llmProvider(), "vastai");
        assertEquals(config.reportWebhookFormat(), "teams");
    }

    @Test
    public void realHealingPropertiesOnMainClasspathLoadsWithoutError() {
        // The actual shipped healing.properties (src/main/resources) - the no-arg
        // constructor's real-world path, distinct from the isolated-default tests above.
        HealingConfig config = new HealingConfig();
        assertTrue(config.enabled());
        assertEquals(config.llmProvider(), "anthropic");
    }
}
