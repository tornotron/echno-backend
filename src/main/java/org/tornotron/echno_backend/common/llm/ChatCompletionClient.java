package org.tornotron.echno_backend.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sends one OpenAI-compatible chat completion and reports how it went, without throwing.
 *
 * <p>The endpoint is any service speaking the OpenAI Chat Completions schema, configured through
 * {@link LlmEndpointProperties}. Every call either reaches the endpoint once or, when the client
 * is switched off or has no key, sends nothing and returns {@link ChatCompletionOutcome#NOT_CONFIGURED}.
 *
 * <h2>One attempt per call</h2>
 *
 * <p>The client never retries. Callers differ in what a retry should look like (compliance fails
 * the whole run and lets its job queue try again; document ingestion backs off per chunk), so the
 * result says which outcomes are worth retrying and the caller does it.
 *
 * <h2>What leaves this class</h2>
 *
 * <p>The API key goes in the {@code Authorization} header and nowhere else: not in a log line, not
 * in {@link ChatCompletionResult#detail()}. Prompt text is never logged. Provider error text is
 * not quoted either, since a provider can echo the prompt (or the key) back in it; the detail
 * names only the provider's short {@code error.code} and {@code error.type} tokens, which are
 * what explain a 400 such as an unknown model slug.
 */
@Slf4j
@Component
public class ChatCompletionClient {

    /** Longest excerpt of an exception message to put in a result's detail. */
    private static final int MAX_EXCERPT_LENGTH = 200;

    /** What a provider's {@code error.code} or {@code error.type} must look like to be quoted. */
    private static final Pattern ERROR_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static final String REDACTED = "[redacted]";

    private final LlmEndpointProperties endpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatCompletionClient(LlmEndpointProperties endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /**
     * Whether the endpoint is switched on and has an API key, decided from configuration with no
     * network call. When false, {@link #complete} sends nothing.
     */
    public boolean isAvailable() {
        String key = endpoint.getApiKey();
        return endpoint.isEnabled() && key != null && !key.isBlank();
    }

    /** Sends the request once and reports the outcome. Never throws for anything the endpoint does. */
    public ChatCompletionResult complete(ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!isAvailable()) {
            log.debug("Chat completion skipped: endpoint switched off or no API key configured");
            return ChatCompletionResult.notConfigured();
        }

        long startedAt = System.nanoTime();
        HttpReply reply;
        try {
            String body = requestBody(request);
            reply = restClient().post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + endpoint.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> read(res));
        } catch (ResourceAccessException e) {
            boolean timedOut = isTimeout(e);
            String detail = timedOut
                    ? "No response from the chat-completions endpoint within the configured timeout."
                    : "The chat-completions endpoint could not be reached (" + causeSummary(e) + ").";
            return failed(timedOut ? ChatCompletionOutcome.TIMEOUT : ChatCompletionOutcome.UNREACHABLE,
                    null, since(startedAt), detail);
        } catch (RuntimeException e) {
            // An unusable base URL or request lands here, before anything reached the network.
            return failed(ChatCompletionOutcome.CLIENT_ERROR, null, since(startedAt),
                    "The chat-completions request could not be sent (" + causeSummary(e) + ").");
        }

        return classify(reply, since(startedAt));
    }

    private ChatCompletionResult classify(HttpReply reply, Duration latency) {
        int status = reply.status();
        if (status == 429) {
            return failed(ChatCompletionOutcome.RATE_LIMITED, status, latency,
                    "The chat-completions endpoint is rate limiting (HTTP 429)." + providerSays(reply.body()));
        }
        if (status >= 500) {
            return failed(ChatCompletionOutcome.SERVER_ERROR, status, latency,
                    "The chat-completions endpoint failed (HTTP " + status + ")." + providerSays(reply.body()));
        }
        if (status >= 400) {
            return failed(ChatCompletionOutcome.CLIENT_ERROR, status, latency,
                    "The chat-completions endpoint refused the request (HTTP " + status + ")."
                            + providerSays(reply.body()));
        }
        if (status < 200 || status >= 300) {
            // A redirect is not followed for a POST, so its body is not an answer to read.
            return failed(ChatCompletionOutcome.CLIENT_ERROR, status, latency,
                    "The chat-completions endpoint answered with HTTP " + status + " instead of a result.");
        }

        ChatCompletionEnvelope envelope;
        try {
            envelope = ChatCompletionEnvelope.parse(reply.body());
        } catch (ChatCompletionEnvelope.MalformedEnvelopeException e) {
            return logged(new ChatCompletionResult(ChatCompletionOutcome.MALFORMED_ENVELOPE, status, null, null,
                    null, null, null, null, latency, e.getMessage(), reply.body()));
        }

        if (envelope.truncated()) {
            return logged(ChatCompletionResult.answered(ChatCompletionOutcome.TRUNCATED, status, envelope, latency,
                    "The answer was cut short at the token limit (finish reason '" + envelope.finishReason()
                            + "', " + envelope.completionTokens() + " completion tokens).",
                    reply.body()));
        }
        if (envelope.content() == null || envelope.content().isBlank()) {
            return logged(ChatCompletionResult.answered(ChatCompletionOutcome.MALFORMED_ENVELOPE, status, envelope,
                    latency, "The response held no message text at choices[0].message.content.", reply.body()));
        }

        log.debug("Chat completion OK in {} ms: model {}, prompt {} / completion {} / total {} tokens, finish {}",
                latency.toMillis(), envelope.model(), envelope.promptTokens(), envelope.completionTokens(),
                envelope.totalTokens(), envelope.finishReason());
        return ChatCompletionResult.answered(ChatCompletionOutcome.OK, status, envelope, latency, null, reply.body());
    }

    private ChatCompletionResult failed(ChatCompletionOutcome outcome, Integer status, Duration latency,
                                        String detail) {
        return logged(ChatCompletionResult.failed(outcome, status, latency, redact(detail)));
    }

    private static ChatCompletionResult logged(ChatCompletionResult result) {
        log.warn("Chat completion {} after {} ms: {}", result.outcome(), result.latency().toMillis(), result.detail());
        return result;
    }

    /**
     * Builds the request body with Jackson so message content is escaped correctly. Field order
     * follows the compliance request this client replaced.
     */
    private String requestBody(ChatCompletionRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", endpoint.getModel());
        root.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.jsonResponseFormat()) {
            root.putObject("response_format").put("type", "json_object");
        }

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userPrompt());

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the chat-completions request", e);
        }
    }

    /**
     * Built per call, as compliance did before, so the timeouts and proxy in force are the ones
     * configured at the time of the call.
     */
    private RestClient restClient() {
        return RestClient.builder()
                .requestFactory(requestFactory())
                .baseUrl(endpoint.getBaseUrl())
                .build();
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(endpoint.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(endpoint.getReadTimeoutSeconds()));
        String proxyHost = endpoint.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = endpoint.getProxyPort();
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            log.debug("Chat completion egress routed through HTTP proxy {}:{}", proxyHost, proxyPort);
        }
        return factory;
    }

    private record HttpReply(int status, String body) {}

    /**
     * Reads status and body. An error response may have no body stream at all, which is not a
     * failure of the call; a timeout while reading a success body is, and propagates.
     */
    private static HttpReply read(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body;
        try (InputStream in = response.getBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (status < 400) {
                throw e;
            }
            body = "";
        }
        return new HttpReply(status, body);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The innermost cause, by class name and message, for a detail sentence. The key is removed
     * before the message is shortened, so a key straddling the cut cannot leave a prefix behind.
     */
    private String causeSummary(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + excerpt(redact(message)));
    }

    /**
     * The provider's error code and type, prefixed for appending to a sentence, when the body is
     * OpenAI-shaped JSON and they are short tokens. Free text from the provider is never quoted.
     */
    private String providerSays(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonNode error;
        try {
            error = objectMapper.readTree(body).path("error");
        } catch (Exception notJson) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String field : new String[] {"code", "type"}) {
            JsonNode value = error.path(field);
            if (value.isValueNode() && ERROR_TOKEN.matcher(value.asText()).matches()) {
                sb.append(sb.isEmpty() ? " Provider error " : ", ").append(field).append(' ').append(value.asText());
            }
        }
        return sb.isEmpty() ? "" : sb.append('.').toString();
    }

    /** Removes the API key from text that may have come back from the provider. */
    private String redact(String text) {
        String key = endpoint.getApiKey();
        if (text == null || key == null || key.isBlank()) {
            return text;
        }
        return text.replace(key.strip(), REDACTED);
    }

    private static String excerpt(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= MAX_EXCERPT_LENGTH
                ? collapsed
                : collapsed.substring(0, MAX_EXCERPT_LENGTH) + "...";
    }

    private static Duration since(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
