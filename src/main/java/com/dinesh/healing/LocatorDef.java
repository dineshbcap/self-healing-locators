package com.dinesh.healing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openqa.selenium.By;

/**
 * A single platform-specific locator definition, plus the human-readable
 * description that healing strategies (deterministic now, LLM in Phase 2)
 * use to understand what the element IS, independent of how it is located.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocatorDef(
        String key,
        String description,
        LocatorStrategy strategy,
        String value) {

    public By toBy() {
        return strategy.toBy(value);
    }

    public LocatorDef withKeyAndDescription(String key, String description) {
        return new LocatorDef(key, description, strategy, value);
    }

    @Override
    public String toString() {
        return "%s[%s=%s]".formatted(key, strategy.json(), value);
    }
}
