package com.dinesh.healing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the configured {@link HealingEngine} from {@code healing.properties} /
 * {@code -D} overrides, so a consumer project can switch LLM providers with a
 * single config flag - no code change, no redeploy of the driver factory.
 *
 * healing.llm.provider = anthropic (default) | ollama | vastai
 *
 * Returns {@link HealingEngine#NO_OP} when healing.llm.enabled=false, or when
 * healing.llm.provider names an unknown provider (logged as a warning rather
 * than a hard failure, consistent with this module's "healing never breaks the
 * run" guarantee).
 */
public final class LlmHealingEngineFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LlmHealingEngineFactory.class);

    private LlmHealingEngineFactory() {
    }

    public static HealingEngine create(HealingConfig config) {
        if (!config.llmEnabled()) {
            return HealingEngine.NO_OP;
        }
        String provider = config.llmProvider();
        return switch (provider) {
            case "anthropic" -> new LlmHealingEngine(config);
            case "ollama" -> new OllamaHealingEngine(config);
            case "vastai" -> new VastAiHealingEngine(config);
            default -> {
                LOG.warn("Unknown healing.llm.provider '{}' - expected anthropic|ollama|vastai. "
                        + "Falling back to NO_OP (no LLM healing).", provider);
                yield HealingEngine.NO_OP;
            }
        };
    }
}
