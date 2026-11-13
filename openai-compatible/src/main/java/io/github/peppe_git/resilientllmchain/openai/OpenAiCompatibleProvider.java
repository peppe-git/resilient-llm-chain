// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.peppe_git.resilientllmchain.LlmProvider;
import io.github.peppe_git.resilientllmchain.LlmResult;
import io.github.peppe_git.resilientllmchain.Prompt;
import io.github.peppe_git.resilientllmchain.StageContext;
import io.github.peppe_git.resilientllmchain.Usage;
import io.github.peppe_git.resilientllmchain.error.ContentRefusedException;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Example adapter for endpoints that speak the OpenAI-compatible {@code POST /chat/completions} API.
 *
 * <p>An example, deliberately small: one system and one user message, optional temperature and output
 * cap, optional streaming. It shows what an adapter owes the chain:
 * <ul>
 *   <li>HTTP error statuses become {@link ProviderHttpException} with the {@code Retry-After} header and
 *       a body excerpt, so the default classifier can route retries, cooldowns and fallbacks;</li>
 *   <li>a refusal by the provider's content filter becomes {@link ContentRefusedException}, which leaves
 *       the breaker alone;</li>
 *   <li>streamed pieces go to {@link StageContext#emit}, which is what lets the chain avoid hedging a
 *       stage that is already writing;</li>
 *   <li>usage is reported, cached input separated, so spend and hedging decisions have numbers.</li>
 * </ul>
 * Bodies are parsed as JSON whatever their {@code Content-Type}: some gateways label JSON as
 * {@code application/octet-stream} or {@code text/plain}. An empty or unreadable body produces an error
 * that says what actually arrived.
 *
 * <p>Vendor-specific switches (reasoning controls, provider routing headers) are out of scope: copy this
 * class and add them in your own code.
 */
public final class OpenAiCompatibleProvider implements LlmProvider<Prompt> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXCERPT_CHARS = 200;

    private final String name;
    private final URI completionsUri;
    private final Supplier<String> apiKey;
    private final String defaultModel;
    private final List<String> models;
    private final Double temperature;
    private final Integer maxTokens;
    private final boolean streaming;
    private final Duration requestTimeout;
    private final HttpClient http;

    private OpenAiCompatibleProvider(Builder b) {
        this.name = b.name;
        String base = b.baseUri.toString();
        this.completionsUri = URI.create((base.endsWith("/") ? base : base + "/") + "chat/completions");
        this.apiKey = b.apiKey;
        this.defaultModel = b.defaultModel;
        this.models = List.copyOf(b.models);
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.streaming = b.streaming;
        this.requestTimeout = b.requestTimeout;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.connectTimeout).build();
    }

    /**
     * @param name    provider name used in chain specs
     * @param baseUri API base, for example {@code https://api.example.com/v1}
     */
    public static Builder builder(String name, URI baseUri) {
        return new Builder(name, baseUri);
    }

    @Override
    public String name() {
        return name;
    }

    /** Available when an API key is configured. */
    @Override
    public boolean available() {
        String key = apiKey.get();
        return key != null && !key.isBlank();
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public List<String> models() {
        return models;
    }

    @Override
    public LlmResult generate(Prompt prompt, String model, StageContext context) throws IOException, InterruptedException {
        String effectiveModel = model != null ? model : defaultModel;
        HttpRequest request = HttpRequest.newBuilder(completionsUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey.get())
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(prompt, effectiveModel), StandardCharsets.UTF_8))
                .build();
        return streaming ? stream(request, effectiveModel, context) : single(request, effectiveModel);
    }

    private String body(Prompt prompt, String model) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        if (model != null) {
            body.put("model", model);
        }
        ArrayNode messages = body.putArray("messages");
        if (prompt.system() != null && !prompt.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", prompt.system());
        }
        messages.addObject().put("role", "user").put("content", prompt.user());
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (streaming) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }
        return JSON.writeValueAsString(body);
    }

    private LlmResult single(HttpRequest request, String model) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] payload = response.body();
        String text = new String(payload, StandardCharsets.UTF_8);
        if (response.statusCode() >= 400) {
            throw new ProviderHttpException(name, response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null), text);
        }
        if (payload.length == 0) {
            throw new IOException(describe(response, model, 0) + ": empty body (connection closed without content)");
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (IOException e) {
            throw new IOException(describe(response, model, payload.length) + ": body is not JSON: " + excerpt(text), e);
        }
        JsonNode choice = root.path("choices").path(0);
        refuseIfFiltered(choice.path("finish_reason").asText(""), model);
        return new LlmResult(choice.path("message").path("content").asText(""),
                textOrNull(root.path("model")), usage(root.path("usage")));
    }

    private LlmResult stream(HttpRequest request, String model, StageContext context)
            throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() >= 400) {
            String body;
            try (Stream<String> lines = response.body()) {
                body = String.join("\n", lines.toList());
            }
            throw new ProviderHttpException(name, response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null), body);
        }
        StringBuilder text = new StringBuilder();
        String answeredModel = null;
        String finishReason = "";
        Usage usage = Usage.ZERO;
        boolean done = false;
        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next().strip();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    done = true;
                    break;
                }
                JsonNode chunk = JSON.readTree(data);
                if (answeredModel == null) {
                    answeredModel = textOrNull(chunk.path("model"));
                }
                JsonNode choice = chunk.path("choices").path(0);
                String piece = choice.path("delta").path("content").asText("");
                if (!piece.isEmpty()) {
                    text.append(piece);
                    context.emit(piece);
                }
                if (!choice.path("finish_reason").isMissingNode() && !choice.path("finish_reason").isNull()) {
                    finishReason = choice.path("finish_reason").asText("");
                }
                if (chunk.hasNonNull("usage")) {
                    usage = usage(chunk.path("usage"));
                }
            }
        }
        if (!done && finishReason.isEmpty()) {
            throw new IOException(name + " model '" + model + "': stream ended before completion after "
                    + text.length() + " characters");
        }
        refuseIfFiltered(finishReason, model);
        return new LlmResult(text.toString(), answeredModel, usage);
    }

    private void refuseIfFiltered(String finishReason, String model) {
        if ("content_filter".equals(finishReason.toLowerCase(Locale.ROOT))) {
            throw new ContentRefusedException(name + " model '" + model + "' declined the request (content filter)");
        }
    }

    private static Usage usage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return Usage.ZERO;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        long cached = Math.min(prompt, usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
        return new Usage(prompt - cached, completion, cached, 0);
    }

    private String describe(HttpResponse<?> response, String model, int bytes) {
        return name + " model '" + model + "' (HTTP " + response.statusCode() + ", content-type "
                + response.headers().firstValue("Content-Type").orElse("absent") + ", " + bytes + " bytes)";
    }

    private static String excerpt(String body) {
        String text = body.replaceAll("\\s+", " ").strip();
        return text.length() > EXCERPT_CHARS ? text.substring(0, EXCERPT_CHARS) + "..." : text;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    public static final class Builder {
        private final String name;
        private final URI baseUri;
        private Supplier<String> apiKey = () -> null;
        private String defaultModel;
        private List<String> models = List.of();
        private Double temperature;
        private Integer maxTokens;
        private boolean streaming;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private HttpClient httpClient;

        private Builder(String name, URI baseUri) {
            this.name = Objects.requireNonNull(name, "name");
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        }

        /** The API key; read at every call, so it can rotate. */
        public Builder apiKey(Supplier<String> value) {
            this.apiKey = Objects.requireNonNull(value, "apiKey");
            return this;
        }

        public Builder defaultModel(String value) {
            this.defaultModel = value;
            return this;
        }

        /** Other models of this endpoint to try after the default one. */
        public Builder models(List<String> value) {
            this.models = Objects.requireNonNull(value, "models");
            return this;
        }

        public Builder temperature(double value) {
            this.temperature = value;
            return this;
        }

        public Builder maxTokens(int value) {
            this.maxTokens = value;
            return this;
        }

        /** Requests a streamed answer and forwards its pieces to {@link StageContext#emit}. */
        public Builder streaming(boolean value) {
            this.streaming = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = Objects.requireNonNull(value, "connectTimeout");
            return this;
        }

        /**
         * Timeout of the HTTP exchange as seen by the JDK client. For a hard cap on the whole call, which
         * also covers a stream that keeps trickling, use the chain's {@code callTimeout}.
         */
        public Builder requestTimeout(Duration value) {
            this.requestTimeout = Objects.requireNonNull(value, "requestTimeout");
            return this;
        }

        /** A client of your own (proxy, TLS settings). When set, {@code connectTimeout} is not used. */
        public Builder httpClient(HttpClient value) {
            this.httpClient = Objects.requireNonNull(value, "httpClient");
            return this;
        }

        public OpenAiCompatibleProvider build() {
            return new OpenAiCompatibleProvider(this);
        }
    }
}
