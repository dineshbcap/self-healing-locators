package com.dinesh.healing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration for the healing layer, loaded from healing.properties on the
 * classpath with System property overrides (so Jenkins jobs can flip flags
 * with -Dhealing.enabled=false etc.).
 *
 * Phase 1 keys:
 *   healing.enabled            master switch (default true)
 *   healing.failOnHeal         treat any heal as a failure - for PR gates (default false)
 *   healing.cache.file         path for the persisted healing cache (default target/healing-cache.json)
 *   healing.report.file        path for the healing report (default target/healing-report.json)
 *
 * Phase 2 keys:
 *   healing.llm.enabled                default false
 *   healing.llm.provider               anthropic (default) | ollama | vastai
 *   healing.llm.confidence.threshold   default 0.7
 *   healing.llm.timeoutSeconds         default 30
 *   healing.llm.maxPageSourceChars     default 60000
 *
 * Provider-specific keys - see {@link LlmHealingEngineFactory} for how
 * healing.llm.provider selects between them:
 *   healing.llm.model / healing.llm.apiKeyEnv              (anthropic)
 *   healing.llm.ollama.baseUrl / healing.llm.ollama.model  (ollama)
 *   healing.llm.vastai.baseUrl / healing.llm.vastai.model / healing.llm.vastai.apiKeyEnv (vastai)
 */
public final class HealingConfig {

    private final Properties props = new Properties();

    public HealingConfig() {
        this("healing.properties");
    }

    public HealingConfig(String classpathResource) {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall back to defaults + system properties
        }
    }

    private String get(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return sys;
        }
        return props.getProperty(key, defaultValue);
    }

    public boolean enabled() {
        return Boolean.parseBoolean(get("healing.enabled", "true"));
    }

    public boolean failOnHeal() {
        return Boolean.parseBoolean(get("healing.failOnHeal", "false"));
    }

    public String cacheFile() {
        return get("healing.cache.file", "target/healing-cache.json");
    }

    public String reportFile() {
        return get("healing.report.file", "target/healing-report.json");
    }

    public boolean llmEnabled() {
        return Boolean.parseBoolean(get("healing.llm.enabled", "false"));
    }

    /** Which HealingEngine implementation {@link LlmHealingEngineFactory} builds. */
    public String llmProvider() {
        return get("healing.llm.provider", "anthropic").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public double llmConfidenceThreshold() {
        return Double.parseDouble(get("healing.llm.confidence.threshold", "0.7"));
    }

    public int llmTimeoutSeconds() {
        return Integer.parseInt(get("healing.llm.timeoutSeconds", "30"));
    }

    /** Hard cap on pruned page source size sent per heal. */
    public int llmMaxPageSourceChars() {
        return Integer.parseInt(get("healing.llm.maxPageSourceChars", "60000"));
    }

    // ---- anthropic ----

    public String llmModel() {
        return get("healing.llm.model", "claude-sonnet-4-6");
    }

    /** Name of the environment variable holding the API key (never the key itself). */
    public String llmApiKeyEnv() {
        return get("healing.llm.apiKeyEnv", "ANTHROPIC_API_KEY");
    }

    // ---- ollama (local) ----

    public String ollamaBaseUrl() {
        return get("healing.llm.ollama.baseUrl", "http://localhost:11434");
    }

    public String ollamaModel() {
        return get("healing.llm.ollama.model", "llama3.1");
    }

    // ---- vastai (self-hosted OpenAI-compatible cloud) ----

    /** No sane default - every rented instance has a different host/port. */
    public String vastAiBaseUrl() {
        return get("healing.llm.vastai.baseUrl", "");
    }

    public String vastAiModel() {
        return get("healing.llm.vastai.model", "");
    }

    /** Name of the environment variable holding the bearer token (never the key itself). */
    public String vastAiApiKeyEnv() {
        return get("healing.llm.vastai.apiKeyEnv", "VASTAI_API_KEY");
    }
}
