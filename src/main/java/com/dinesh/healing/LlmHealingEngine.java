package com.dinesh.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 2: LLM-backed healing via the Anthropic Messages API, using only
 * java.net.http + Jackson (no new dependencies - bank-friendly).
 *
 * The API key is read from the ANTHROPIC_API_KEY environment variable (name
 * configurable via healing.llm.apiKeyEnv). It is NEVER logged and never
 * written to any report or cache artifact.
 *
 * Payload sent per heal: locator key, description, original locator, and the
 * PRUNED + PII-REDACTED page source only. Callers (SelfHealingElementLocator)
 * are responsible for running PageSourcePruner + PiiRedactor first.
 *
 * The HTTP layer is injectable ({@link Transport}) so unit tests exercise the
 * full prompt/parse path without network access.
 */
public final class LlmHealingEngine implements HealingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LlmHealingEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Injectable HTTP seam for tests. */
    @FunctionalInterface
    public interface Transport {
        String post(String url, String apiKey, String jsonBody) throws IOException, InterruptedException;
    }

    private final HealingConfig config;
    private final Transport transport;
    private final String apiKey;

    public LlmHealingEngine(HealingConfig config) {
        this(config, defaultTransport(config), resolveApiKey(config));
    }

    LlmHealingEngine(HealingConfig config, Transport transport, String apiKey) {
        this.config = config;
        this.transport = transport;
        this.apiKey = apiKey;
    }

    private static String resolveApiKey(HealingConfig config) {
        String env = config.llmApiKeyEnv();
        String key = System.getenv(env);
        if (key == null || key.isBlank()) {
            LOG.warn("LLM healing enabled but env var {} is not set - LLM heals will be skipped", env);
        }
        return key;
    }

    private static Transport defaultTransport(HealingConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration timeout = Duration.ofSeconds(config.llmTimeoutSeconds());
        return (url, apiKey, body) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Anthropic API returned HTTP " + response.statusCode()
                        + ": " + truncate(response.body(), 500));
            }
            return response.body();
        };
    }

    @Override
    public Optional<Proposal> propose(String locatorKey, String description, String prunedPageSource) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        if (prunedPageSource == null || prunedPageSource.isBlank()) {
            return Optional.empty();
        }
        String pageSource = truncate(prunedPageSource, config.llmMaxPageSourceChars());
        try {
            String requestBody = buildRequestBody(locatorKey, description, pageSource);
            String responseBody = transport.post(API_URL, apiKey, requestBody);
            return parseProposal(responseBody);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("LLM healing call failed for '{}': {}", locatorKey, e.toString());
            return Optional.empty();
        }
    }

    String buildRequestBody(String locatorKey, String description, String pageSource)
            throws IOException {
        String prompt = """
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

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.llmModel());
        root.put("max_tokens", 300);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return MAPPER.writeValueAsString(root);
    }

    Optional<Proposal> parseProposal(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            String cleaned = text.toString()
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
            return Optional.of(new Proposal(strategy, value, confidence));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Could not parse LLM healing response: {}", e.toString());
            return Optional.empty();
        }
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

    /** Rejects xpaths like //X[3] whose only discriminator is a positional index. */
    static boolean looksIndexBased(String xpath) {
        String lower = xpath.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\[\\s*\\d+\\s*]\\s*$") && !lower.contains("@");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
