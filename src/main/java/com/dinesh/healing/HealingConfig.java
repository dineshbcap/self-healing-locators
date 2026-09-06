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
 * Phase 3 keys (reporting):
 *   healing.metrics.file               CSV, one row appended per run (default target/healing-metrics.csv)
 *   healing.events.file                CSV, one row per heal event, for Phase 5 churn analysis
 *                                       (default target/healing-events.csv)
 *   healing.report.webhook.enabled     default false
 *   healing.report.webhook.url         Slack incoming-webhook or Teams workflow-webhook URL (default blank)
 *   healing.report.webhook.format      slack (default) | teams | teams-messagecard (legacy Office 365 connector)
 *   healing.report.webhook.timeoutSeconds  default 10
 *   healing.report.webhook.maxItems    heals listed before truncating to "+N more" (default 20)
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
 *   healing.llm.model / healing.llm.apiKeyEnv                                (anthropic)
 *   healing.llm.ollama.baseUrl / healing.llm.ollama.model / .numCtx / .apiKeyEnv (ollama)
 *   healing.llm.vastai.baseUrl / healing.llm.vastai.model / .apiKeyEnv       (vastai)
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

    /**
     * One CSV row appended per suite run (timestamp, build id, heal count) - the
     * healing-rate-per-release trend line. Living under target/ means it only
     * accumulates within a single build; point this at a path your Jenkins job
     * restores from the previous run (e.g. via copyArtifacts) before the suite
     * starts if you want it to persist across builds.
     */
    public String metricsFile() {
        return get("healing.metrics.file", "target/healing-metrics.csv");
    }

    /**
     * One CSV row per individual heal event (timestamp, build id, platform, locator key,
     * healing strategy) - finer-grained than {@link #metricsFile()}'s per-run total, since
     * {@link HealingChurnAnalyzer} (Phase 5) needs to know WHICH screen healed, not just
     * how many times something healed. Same cross-build persistence caveat as
     * {@link #metricsFile()} applies - living under target/ means it resets every
     * `mvn clean`.
     */
    public String eventsFile() {
        return get("healing.events.file", "target/healing-events.csv");
    }

    // ---- Phase 3: report webhook (Slack / Teams) ----

    public boolean reportWebhookEnabled() {
        return Boolean.parseBoolean(get("healing.report.webhook.enabled", "false"));
    }

    /** Slack incoming-webhook URL, or a Teams Power Automate workflow-webhook URL. */
    public String reportWebhookUrl() {
        return get("healing.report.webhook.url", "");
    }

    /** One of "slack" (default), "teams", or "teams-messagecard" (legacy Office 365 connector). */
    public String reportWebhookFormat() {
        return get("healing.report.webhook.format", "slack").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public int reportWebhookTimeoutSeconds() {
        return Integer.parseInt(get("healing.report.webhook.timeoutSeconds", "10"));
    }

    /** Heals listed in the message body before truncating to "...and N more". */
    public int reportWebhookMaxItems() {
        return Integer.parseInt(get("healing.report.webhook.maxItems", "20"));
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

    /**
     * Context window (tokens) requested from Ollama for each heal call. Ollama defaults
     * new sessions to 4096 regardless of the model's real capacity unless a request sets
     * this explicitly, so size it to comfortably fit {@link #llmMaxPageSourceChars()} - a
     * rough rule of thumb is (maxPageSourceChars / 3) + 1000 for prompt overhead - without
     * exceeding the model's own max context (see {@code ollama show <model>}).
     */
    public int ollamaNumCtx() {
        return Integer.parseInt(get("healing.llm.ollama.numCtx", "8192"));
    }

    /**
     * Name of the environment variable holding a bearer token, for Ollama instances that
     * sit behind an authenticating reverse proxy/tunnel (e.g. a vast.ai Instance Portal
     * quick tunnel) rather than running locally/unauthenticated. Blank by default - no
     * Authorization header is sent unless this is set to a non-blank env var name.
     */
    public String ollamaApiKeyEnv() {
        return get("healing.llm.ollama.apiKeyEnv", "");
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
