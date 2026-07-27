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
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HealingEngine backed by an OpenAI-compatible chat-completions endpoint you
 * run yourself on rented GPU capacity (vast.ai is the reference case, but any
 * self-hosted vLLM / text-generation-webui / LM Studio server works the same
 * way). One of three {@link HealingEngine} providers - see also
 * {@link LlmHealingEngine} (Anthropic) and {@link OllamaHealingEngine} (local).
 * Prefer {@link LlmHealingEngineFactory} to select the provider from config.
 *
 * vast.ai itself only rents compute - there is no fixed "vast.ai API". You
 * deploy an OpenAI-compatible inference server (vLLM, text-generation-webui,
 * Ollama's OpenAI-compatible mode, etc.) on the rented instance and point this
 * engine at that instance's public URL. Because that URL is reachable from the
 * open internet (unlike local Ollama), treat the API key as required in
 * practice even though the wire format allows going without one.
 *
 * Configure with:
 *   healing.llm.provider=vastai
 *   healing.llm.vastai.baseUrl    e.g. http://&lt;instance-ip&gt;:8000/v1 (no default - required)
 *   healing.llm.vastai.model      the model name your server was started with
 *   healing.llm.vastai.apiKeyEnv  env var holding the bearer token (default VASTAI_API_KEY)
 *
 * The HTTP layer is injectable ({@link Transport}) so unit tests exercise the
 * full prompt/parse path without network access.
 */
public final class VastAiHealingEngine implements HealingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(VastAiHealingEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Injectable HTTP seam for tests. */
    @FunctionalInterface
    public interface Transport {
        String post(String url, String apiKey, String jsonBody) throws IOException, InterruptedException;
    }

    private final HealingConfig config;
    private final Transport transport;
    private final String apiKey;

    public VastAiHealingEngine(HealingConfig config) {
        this(config, defaultTransport(config), resolveApiKey(config));
    }

    VastAiHealingEngine(HealingConfig config, Transport transport, String apiKey) {
        this.config = config;
        this.transport = transport;
        this.apiKey = apiKey;
    }

    private static String resolveApiKey(HealingConfig config) {
        String env = config.vastAiApiKeyEnv();
        String key = System.getenv(env);
        if (key == null || key.isBlank()) {
            LOG.warn("vast.ai healing enabled but env var {} is not set - calls will be sent "
                    + "without an Authorization header (only safe if your server enforces its "
                    + "own network-level access control)", env);
        }
        return key;
    }

    private static Transport defaultTransport(HealingConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration timeout = Duration.ofSeconds(config.llmTimeoutSeconds());
        return (url, apiKey, body) -> {
            Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("vast.ai endpoint returned HTTP " + response.statusCode()
                        + ": " + LlmResponseParser.truncate(response.body(), 500));
            }
            return response.body();
        };
    }

    @Override
    public Optional<Proposal> propose(String locatorKey, String description, String prunedPageSource) {
        String baseUrl = config.vastAiBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            LOG.warn("LLM healing provider is 'vastai' but healing.llm.vastai.baseUrl is not "
                    + "set - LLM heals will be skipped");
            return Optional.empty();
        }
        if (prunedPageSource == null || prunedPageSource.isBlank()) {
            return Optional.empty();
        }
        String pageSource = truncate(prunedPageSource, config.llmMaxPageSourceChars());
        try {
            String requestBody = buildRequestBody(locatorKey, description, pageSource);
            String responseBody = transport.post(chatUrl(baseUrl), apiKey, requestBody);
            return parseProposal(responseBody);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("vast.ai healing call failed for '{}': {}", locatorKey, e.toString());
            return Optional.empty();
        }
    }

    private static String chatUrl(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/chat/completions";
    }

    String buildRequestBody(String locatorKey, String description, String pageSource) throws IOException {
        String prompt = LlmResponseParser.buildPrompt(locatorKey, description, pageSource);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.vastAiModel());
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
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            return LlmResponseParser.parseProposal(text);
        } catch (IOException e) {
            LOG.warn("Could not parse vast.ai healing response: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
