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
import java.util.Optional;

/**
 * HealingEngine backed by a local Ollama server (https://ollama.com), talking to
 * its native {@code /api/chat} endpoint. One of three {@link HealingEngine}
 * providers - see also {@link LlmHealingEngine} (Anthropic) and
 * {@link VastAiHealingEngine} (self-hosted OpenAI-compatible cloud). Prefer
 * {@link LlmHealingEngineFactory} to select the provider from config.
 *
 * No API key by default: Ollama is expected to run unauthenticated on localhost or a
 * trusted host you control, which is why this is the option for teams that cannot let
 * a page source (even pruned/redacted) leave their network at all. If instead you're
 * reaching a remote Ollama through an authenticating reverse proxy/tunnel (e.g. a
 * vast.ai Instance Portal quick tunnel gating access with a bearer token), set
 * healing.llm.ollama.apiKeyEnv to send it as an Authorization header.
 *
 * Configure with:
 *   healing.llm.provider=ollama
 *   healing.llm.ollama.baseUrl   default http://localhost:11434
 *   healing.llm.ollama.model     default llama3.1 (must already be `ollama pull`ed)
 *   healing.llm.ollama.apiKeyEnv env var holding a bearer token - optional, unset by default
 *
 * The HTTP layer is injectable ({@link Transport}) so unit tests exercise the
 * full prompt/parse path without network access.
 */
public final class OllamaHealingEngine implements HealingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaHealingEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Injectable HTTP seam for tests. */
    @FunctionalInterface
    public interface Transport {
        String post(String url, String apiKey, String jsonBody) throws IOException, InterruptedException;
    }

    private final HealingConfig config;
    private final Transport transport;
    private final String apiKey;

    public OllamaHealingEngine(HealingConfig config) {
        this(config, defaultTransport(config), resolveApiKey(config));
    }

    OllamaHealingEngine(HealingConfig config, Transport transport) {
        this(config, transport, resolveApiKey(config));
    }

    OllamaHealingEngine(HealingConfig config, Transport transport, String apiKey) {
        this.config = config;
        this.transport = transport;
        this.apiKey = apiKey;
    }

    private static String resolveApiKey(HealingConfig config) {
        String env = config.ollamaApiKeyEnv();
        if (env == null || env.isBlank()) {
            return null;
        }
        String key = System.getenv(env);
        if (key == null || key.isBlank()) {
            LOG.warn("healing.llm.ollama.apiKeyEnv is set to '{}' but that env var is empty - "
                    + "calls will be sent without an Authorization header", env);
            return null;
        }
        return key;
    }

    private static Transport defaultTransport(HealingConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration timeout = Duration.ofSeconds(config.llmTimeoutSeconds());
        return (url, apiKey, body) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
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
                throw new IOException("Ollama returned HTTP " + response.statusCode()
                        + ": " + LlmResponseParser.truncate(response.body(), 500));
            }
            return response.body();
        };
    }

    @Override
    public Optional<Proposal> propose(String locatorKey, String description, String prunedPageSource) {
        if (prunedPageSource == null || prunedPageSource.isBlank()) {
            return Optional.empty();
        }
        String pageSource = truncate(prunedPageSource, config.llmMaxPageSourceChars());
        String url = chatUrl();
        try {
            String requestBody = buildRequestBody(locatorKey, description, pageSource);
            String responseBody = transport.post(url, apiKey, requestBody);
            return parseProposal(responseBody);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Ollama healing call failed for '{}': {}", locatorKey, e.toString());
            return Optional.empty();
        }
    }

    private String chatUrl() {
        String base = config.ollamaBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/chat";
    }

    String buildRequestBody(String locatorKey, String description, String pageSource) throws IOException {
        String prompt = LlmResponseParser.buildPrompt(locatorKey, description, pageSource);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.ollamaModel());
        root.put("stream", false);
        // Reasoning models (e.g. qwen3) otherwise burn unbounded time on a chain-of-thought
        // trace before the actual answer; ignored harmlessly by models that don't support it.
        root.put("think", false);
        ObjectNode options = root.putObject("options");
        options.put("temperature", 0);
        // Ollama defaults num_ctx to 4096 regardless of the model's real context length,
        // silently truncating/context-shifting (slow, sometimes pathologically so) once the
        // prompt - dominated by the pruned page source - exceeds it. Size this to comfortably
        // fit healing.llm.maxPageSourceChars, capped at the model's own max context.
        options.put("num_ctx", config.ollamaNumCtx());
        // Matches the 300-token cap LlmHealingEngine/VastAiHealingEngine already use - the
        // answer is always a short JSON object, never worth an unbounded generation.
        options.put("num_predict", 300);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return MAPPER.writeValueAsString(root);
    }

    Optional<Proposal> parseProposal(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            String text = root.path("message").path("content").asText("");
            return LlmResponseParser.parseProposal(text);
        } catch (IOException e) {
            LOG.warn("Could not parse Ollama healing response: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
