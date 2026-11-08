package org.tornotron.echno_backend.common.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The shared chat-completions client against a loopback HTTP server the test owns, so every case
 * goes through the real request factory, timeouts and status handling with no outside endpoint.
 */
class ChatCompletionClientTest {

    private static final String API_KEY = "test-key-for-the-stub-server";

    /**
     * Pinned to IPv4 on both sides, so a JVM that prefers IPv6 cannot bind the server to {@code ::1}
     * while the client dials {@code 127.0.0.1}.
     */
    private static final InetAddress LOOPBACK_V4 = loopbackV4();

    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    private volatile int replyStatus = 200;
    private volatile String replyBody = "";
    private volatile long replyDelayMillis = 0;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(LOOPBACK_V4, 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (replyDelayMillis > 0) {
                    Thread.sleep(replyDelayMillis);
                }
                byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(replyStatus, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (Exception ignored) {
                // The client gave up (timeout test); nothing to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();

        clientLogger = (Logger) LoggerFactory.getLogger(ChatCompletionClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void stopServer() {
        clientLogger.detachAppender(logs);
        server.stop(0);
    }

    // ---- request ---------------------------------------------------------------------------

    @Test
    void sendsSystemAndUserMessagesWithThePerCallTokenCap() throws Exception {
        answer(200, envelope("{}", "stop", usage(10, 2, 12)));

        client(endpoint()).complete(ChatCompletionRequest.of("You read tenders.", "Page 1 text", 1500));

        JsonNode sent = json.readTree(requestBodies.get(0));
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1500);
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("You read tenders.");
        assertThat(sent.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("messages").get(1).path("content").asText()).isEqualTo("Page 1 text");
        assertThat(authorizations.get(0)).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void temperatureAndResponseFormatAreSentOnlyWhenAsked() throws Exception {
        answer(200, envelope("{}", "stop", usage(10, 2, 12)));
        ChatCompletionClient client = client(endpoint());

        client.complete(ChatCompletionRequest.of("s", "u", 100));
        client.complete(ChatCompletionRequest.of("s", "u", 100).withTemperature(0.2));
        client.complete(ChatCompletionRequest.of("s", "u", 100).withJsonResponseFormat());

        JsonNode plain = json.readTree(requestBodies.get(0));
        assertThat(plain.has("temperature")).isFalse();
        assertThat(plain.has("response_format")).isFalse();

        JsonNode withTemperature = json.readTree(requestBodies.get(1));
        assertThat(withTemperature.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(withTemperature.has("response_format")).isFalse();

        JsonNode withFormat = json.readTree(requestBodies.get(2));
        assertThat(withFormat.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(withFormat.has("temperature")).isFalse();
    }

    @Test
    void maxTokensIsPerCall() throws Exception {
        answer(200, envelope("{}", "stop", usage(10, 2, 12)));
        ChatCompletionClient client = client(endpoint());

        client.complete(ChatCompletionRequest.of("s", "u", 1500));
        client.complete(ChatCompletionRequest.of("s", "u", 4096));

        assertThat(json.readTree(requestBodies.get(0)).path("max_tokens").asInt()).isEqualTo(1500);
        assertThat(json.readTree(requestBodies.get(1)).path("max_tokens").asInt()).isEqualTo(4096);
    }

    @Test
    void refusesARequestWithNoTokenCap() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatCompletionRequest.of("s", "u", 0));
    }

    // ---- usage and finish reason -------------------------------------------------------------

    @Test
    void readsContentUsageModelAndLatency() {
        answer(200, envelope("[{\"a\":1}]", "stop", usage(1200, 340, 1540)));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 1500));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.OK);
        assertThat(result.isOk()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.content()).isEqualTo("[{\"a\":1}]");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.promptTokens()).isEqualTo(1200);
        assertThat(result.completionTokens()).isEqualTo(340);
        assertThat(result.totalTokens()).isEqualTo(1540);
        assertThat(result.model()).isEqualTo("reported-model-v2");
        assertThat(result.latency()).isPositive();
        assertThat(result.detail()).isNull();
    }

    @Test
    void usageAbsentIsNullNotZero() {
        answer(200, envelope("ok", "stop", null));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.OK);
        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
    }

    @Test
    void partialUsageKeepsWhatWasReported() {
        answer(200, envelope("ok", "stop", Map.of("prompt_tokens", 800)));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.promptTokens()).isEqualTo(800);
        assertThat(result.completionTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
    }

    @Test
    void totalIsTheSumWhenOnlyThePartsWereReported() {
        answer(200, envelope("ok", "stop", Map.of("prompt_tokens", 800, "completion_tokens", 90)));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.totalTokens()).isEqualTo(890);
    }

    @Test
    void finishReasonLengthIsTruncatedAndKeepsWhatCameBack() {
        answer(200, envelope("[{\"a\":1},{\"a\":", "length", usage(900, 1500, 2400)));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 1500));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.TRUNCATED);
        assertThat(result.outcome().isRetryable()).isFalse();
        assertThat(result.finishReason()).isEqualTo("length");
        assertThat(result.completionTokens()).isEqualTo(1500);
        assertThat(result.content()).startsWith("[{\"a\":1}");
        assertThat(result.detail()).contains("cut short");
    }

    @Test
    void aNullFinishReasonIsNotTruncation() {
        answer(200, envelope("done", null, usage(5, 1, 6)));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.OK);
        assertThat(result.finishReason()).isNull();
    }

    @Test
    void stopReasonIsReadWhenFinishReasonIsAbsent() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"x\"},\"stop_reason\":\"max_tokens\"}]}";

        ChatCompletionEnvelope envelope = ChatCompletionEnvelope.parse(body);

        assertThat(envelope.finishReason()).isEqualTo("max_tokens");
        assertThat(envelope.truncated()).isTrue();
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    void http429IsRateLimitedAndRetryable() {
        answer(429, "{\"error\":{\"message\":\"Too many requests\",\"code\":\"rate_limit_exceeded\"}}");

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.RATE_LIMITED);
        assertThat(result.outcome().isRetryable()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(429);
        assertThat(result.detail()).contains("rate_limit_exceeded").doesNotContain("Too many requests");
        assertThat(result.responseBody()).isNull();
    }

    @Test
    void http5xxIsAServerErrorAndRetryable() {
        answer(503, "<html>Service Unavailable</html>");

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.SERVER_ERROR);
        assertThat(result.outcome().isRetryable()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(503);
    }

    @Test
    void otherHttp4xxIsAClientErrorThatSaysWhy() {
        answer(400, "{\"error\":{\"message\":\"The model 'test-model' does not exist\","
                + "\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}");

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.CLIENT_ERROR);
        assertThat(result.outcome().isRetryable()).isFalse();
        assertThat(result.detail())
                .contains("HTTP 400")
                .contains("model_not_found")
                .contains("invalid_request_error")
                .doesNotContain("does not exist");
    }

    @Test
    void aRedirectIsNotReadAsAnAnswer() {
        answer(302, envelope("from somewhere else", "stop", null));

        ChatCompletionResult result = client(endpoint()).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.CLIENT_ERROR);
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(result.content()).isNull();
    }

    @Test
    void noAnswerWithinTheReadTimeoutIsATimeout() {
        answer(200, envelope("late", "stop", null));
        replyDelayMillis = 5_000;
        LlmEndpointProperties endpoint = endpoint();
        endpoint.setReadTimeoutSeconds(1);

        long startedAt = System.nanoTime();
        ChatCompletionResult result = client(endpoint).complete(ChatCompletionRequest.of("s", "u", 100));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.TIMEOUT);
        assertThat(result.outcome().isRetryable()).isTrue();
        assertThat(elapsedMillis).isLessThan(4_000);
    }

    @Test
    void aRefusedConnectionIsUnreachableAndRetryable() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK_V4)) {
            closedPort = probe.getLocalPort();
        }
        LlmEndpointProperties endpoint = endpoint();
        endpoint.setBaseUrl("http://127.0.0.1:" + closedPort + "/v1");

        ChatCompletionResult result = client(endpoint).complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.UNREACHABLE);
        assertThat(result.outcome().isRetryable()).isTrue();
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    void aSuccessBodyThatIsNotAnEnvelopeIsMalformed() {
        ChatCompletionClient client = client(endpoint());

        answer(200, "<html>proxy login</html>");
        ChatCompletionResult notJson = client.complete(ChatCompletionRequest.of("s", "u", 100));
        answer(200, "");
        ChatCompletionResult empty = client.complete(ChatCompletionRequest.of("s", "u", 100));
        answer(200, "{\"id\":\"x\",\"choices\":[]}");
        ChatCompletionResult noChoices = client.complete(ChatCompletionRequest.of("s", "u", 100));
        answer(200, envelope("", "stop", usage(5, 0, 5)));
        ChatCompletionResult emptyText = client.complete(ChatCompletionRequest.of("s", "u", 100));
        answer(200, envelope("  \n ", "stop", usage(5, 1, 6)));
        ChatCompletionResult blankText = client.complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(List.of(notJson, empty, noChoices, emptyText, blankText))
                .extracting(ChatCompletionResult::outcome)
                .containsOnly(ChatCompletionOutcome.MALFORMED_ENVELOPE);
        assertThat(notJson.outcome().isRetryable()).isFalse();
        assertThat(notJson.responseBody()).isEqualTo("<html>proxy login</html>");
    }

    // ---- switched off --------------------------------------------------------------------------

    @Test
    void disabledSendsNothingAndReportsItselfUnavailable() {
        LlmEndpointProperties endpoint = endpoint();
        endpoint.setEnabled(false);
        ChatCompletionClient client = client(endpoint);

        ChatCompletionResult result = client.complete(ChatCompletionRequest.of("s", "u", 100));

        assertThat(client.isAvailable()).isFalse();
        assertThat(result.outcome()).isEqualTo(ChatCompletionOutcome.NOT_CONFIGURED);
        assertThat(result.outcome().isRetryable()).isFalse();
        assertThat(requests).hasValue(0);
    }

    @Test
    void aBlankKeySendsNothing() {
        LlmEndpointProperties endpoint = endpoint();
        endpoint.setApiKey("   ");
        ChatCompletionClient client = client(endpoint);

        assertThat(client.isAvailable()).isFalse();
        assertThat(client.complete(ChatCompletionRequest.of("s", "u", 100)).outcome())
                .isEqualTo(ChatCompletionOutcome.NOT_CONFIGURED);
        assertThat(requests).hasValue(0);
    }

    /** The operator's switch is the existing compliance one, so turning it off stops every caller. */
    @Test
    void theComplianceSwitchTurnsTheClientOff() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "compliance.ai.enabled", "false",
                "compliance.ai.api-key", API_KEY,
                "compliance.ai.model", "bound-model")));

        LlmEndpointProperties bound =
                binder.bind("compliance.ai", Bindable.of(LlmEndpointProperties.class)).get();

        assertThat(bound.getModel()).isEqualTo("bound-model");
        assertThat(new ChatCompletionClient(bound).isAvailable()).isFalse();
    }

    // ---- the key -------------------------------------------------------------------------------

    @Test
    void neitherTheKeyNorProviderTextAppearsInAnyDetailOrLogLine() throws Exception {
        String prompt = "Clause 14.2 of the tender for the synthetic test project";
        ChatCompletionClient client = client(endpoint());
        List<ChatCompletionResult> results = new ArrayList<>();

        // 403 rather than 401 for the error code: the JDK client drops a 401's body on a streamed
        // request, so a 401 never gets as far as reading it. It is checked below all the same.
        answer(403, "{\"error\":{\"message\":\"Key not permitted for this model: " + API_KEY + "\","
                + "\"code\":\"permission_denied\"}}");
        results.add(client.complete(ChatCompletionRequest.of("s", "u", 100)));
        answer(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + API_KEY + "\"}}");
        results.add(client.complete(ChatCompletionRequest.of("s", "u", 100)));
        answer(500, "upstream rejected Bearer " + API_KEY);
        results.add(client.complete(ChatCompletionRequest.of("s", "u", 100)));
        answer(400, "{\"error\":{\"message\":\"Could not process: " + prompt + "\","
                + "\"code\":\"" + API_KEY + "\"}}");
        results.add(client.complete(ChatCompletionRequest.of("s", prompt, 100)));
        answer(200, "not json " + API_KEY);
        results.add(client.complete(ChatCompletionRequest.of("s", "u", 100)));

        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK_V4)) {
            LlmEndpointProperties closed = endpoint();
            closed.setBaseUrl("http://127.0.0.1:" + probe.getLocalPort() + "/nowhere");
            probe.close();
            results.add(new ChatCompletionClient(closed).complete(ChatCompletionRequest.of("s", "u", 100)));
        }

        assertThat(results).extracting(ChatCompletionResult::detail)
                .allSatisfy(detail -> assertThat(detail).isNotNull()
                        .doesNotContain(API_KEY)
                        .doesNotContain("test-key-for")
                        .doesNotContain(prompt)
                        .doesNotContain("Key not permitted"));
        assertThat(results.get(0).detail()).contains("permission_denied");
        assertThat(results.get(1).outcome()).isEqualTo(ChatCompletionOutcome.CLIENT_ERROR);
        assertThat(endpoint().toString()).doesNotContain(API_KEY);

        assertThat(logs.list).as("the failures were logged, so the check below is not vacuous").hasSizeGreaterThanOrEqualTo(6);
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(API_KEY).doesNotContain(prompt);
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(API_KEY);
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private LlmEndpointProperties endpoint() {
        LlmEndpointProperties endpoint = new LlmEndpointProperties();
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        endpoint.setApiKey(API_KEY);
        endpoint.setModel("test-model");
        endpoint.setReadTimeoutSeconds(10);
        return endpoint;
    }

    private static InetAddress loopbackV4() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatCompletionClient client(LlmEndpointProperties endpoint) {
        return new ChatCompletionClient(endpoint);
    }

    private void answer(int status, String body) {
        replyStatus = status;
        replyBody = body;
        replyDelayMillis = 0;
    }

    private static Map<String, Object> usage(int prompt, int completion, int total) {
        return Map.of("prompt_tokens", prompt, "completion_tokens", completion, "total_tokens", total);
    }

    /** A chat-completions body, built with Jackson so the escaping is the provider's. */
    private String envelope(String content, String finishReason, Map<String, Object> usage) {
        var root = json.createObjectNode();
        root.put("id", "chatcmpl-1");
        root.put("model", "reported-model-v2");
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", content);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        if (usage != null) {
            root.set("usage", json.valueToTree(usage));
        }
        return root.toString();
    }
}
