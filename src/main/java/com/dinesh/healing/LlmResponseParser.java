package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Provider-agnostic prompt template and response parsing shared by every
 * {@link HealingEngine} LLM implementation ({@link LlmHealingEngine} /
 * Anthropic, {@link OllamaHealingEngine}, {@link VastAiHealingEngine}).
 *
 * Each provider differs only in the request/response envelope (auth header,
 * JSON wrapper shape). Once a provider has pulled the assistant's raw text
 * reply out of its envelope, parsing that text into a {@link HealingEngine.Proposal}
 * is identical everywhere - including tolerating commentary the model adds
 * despite being told not to (see extractJsonObject).
 */
final class LlmResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(LlmResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmResponseParser() {
    }

    static String buildPrompt(String locatorKey, String description, String pageSource) {
        return """
                A mobile UI test failed to find an element. Identify it in the page source \
                and return the most robust replacement locator.

                Element key: %s
                Element description: %s

                Locator preference order for Android: accessibilityId > id > stable attribute-based xpath. \
                NEVER return an index-based xpath such as //android.widget.Button[3].

                Locator preference order for IOS: accessibilityId > iOS Predicate > Class Chain > stable attribute-based xpath. \
                NEVER return an index-based xpath such as //android.widget.Button[3].

                Respond ONLY with JSON, no markdown fences, no commentary:
                {"strategy": "accessibilityId" | "id" | "xpath", "value": "...", "confidence": 0.0-1.0}
                If no element in the page source matches the description, respond exactly:
                {"strategy": "none"}

                PAGE SOURCE (pruned, PII-redacted):
                %s
                """.formatted(locatorKey, description, pageSource);
    }

    /** Parses the assistant's raw text reply (already unwrapped from the provider envelope). */
    static Optional<HealingEngine.Proposal> parseProposal(String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        String cleaned = rawText
                .replace("```json", "")
                .replace("```", "")
                .trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        String jsonCandidate = extractJsonObject(cleaned);
        if (jsonCandidate == null) {
            LOG.warn("LLM response contained no JSON object: {}", truncate(cleaned, 200));
            return Optional.empty();
        }
        try {
            JsonNode proposal = MAPPER.readTree(jsonCandidate);
            String strategyRaw = proposal.path("strategy").asText("none");
            if ("none".equalsIgnoreCase(strategyRaw)) {
                LOG.info("LLM reports element not present on screen - no heal proposed");
                return Optional.empty();
            }
            LocatorStrategy strategy = LocatorStrategy.fromJson(strategyRaw);
            String value = proposal.path("value").asText("");
            double confidence = proposal.path("confidence").asDouble(0.0);
            if (value.isBlank()) {
                return Optional.empty();
            }
            if (strategy == LocatorStrategy.XPATH && looksIndexBased(value)) {
                LOG.info("Rejecting index-based xpath from LLM: {}", value);
                return Optional.empty();
            }
            return Optional.of(new HealingEngine.Proposal(strategy, value, confidence));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Could not parse LLM healing response: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Rejects xpaths like //X[3] whose only discriminator is a positional index. */
    static boolean looksIndexBased(String xpath) {
        String lower = xpath.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\[\\s*\\d+\\s*]\\s*$") && !lower.contains("@");
    }

    /**
     * Pulls the {@code {...}} JSON object out of a model response that may carry
     * leading/trailing commentary despite the prompt asking for JSON only
     * (e.g. "The description asks for... {"strategy": "id", ...}").
     * Returns null if no brace pair is present.
     */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
