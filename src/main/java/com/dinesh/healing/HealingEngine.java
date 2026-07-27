package com.dinesh.healing;

import org.openqa.selenium.By;

import java.util.Optional;

/**
 * The Phase 2 seam. An implementation receives the failed locator's key and
 * description plus a pruned page source, and proposes a replacement locator.
 *
 * Phase 1 ships only {@link #NO_OP}. Phase 2 adds three interchangeable LLM
 * implementations, all behind PageSourcePruner + PII redaction and the
 * healing.llm.enabled flag - no changes needed in SelfHealingElementLocator:
 *   - {@link LlmHealingEngine}     - Anthropic Messages API (cloud)
 *   - {@link OllamaHealingEngine}  - local Ollama server
 *   - {@link VastAiHealingEngine}  - self-hosted OpenAI-compatible cloud (vast.ai etc.)
 * Use {@link LlmHealingEngineFactory#create} to pick one via healing.llm.provider
 * rather than constructing a specific engine in code.
 */
public interface HealingEngine {

    record Proposal(LocatorStrategy strategy, String value, double confidence) {
        public By toBy() {
            return strategy.toBy(value);
        }
    }

    Optional<Proposal> propose(String locatorKey, String description, String prunedPageSource);

    HealingEngine NO_OP = (key, description, pageSource) -> Optional.empty();
}
