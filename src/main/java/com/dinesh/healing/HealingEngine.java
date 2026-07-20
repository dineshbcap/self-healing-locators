package com.dinesh.healing;

import org.openqa.selenium.By;

import java.util.Optional;

/**
 * The Phase 2 seam. An implementation receives the failed locator's key and
 * description plus a pruned page source, and proposes a replacement locator.
 *
 * Phase 1 ships only {@link #NO_OP}. Phase 2 adds LlmHealingEngine (Claude
 * Messages API + PageSourcePruner + PII redaction) behind the
 * healing.llm.enabled flag - no changes needed in SelfHealingElementLocator.
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
