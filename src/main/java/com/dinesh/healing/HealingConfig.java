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
 * Phase 2 keys (read but unused until the LLM engine lands):
 *   healing.llm.enabled                default false
 *   healing.llm.confidence.threshold   default 0.7
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

    public double llmConfidenceThreshold() {
        return Double.parseDouble(get("healing.llm.confidence.threshold", "0.7"));
    }
}
