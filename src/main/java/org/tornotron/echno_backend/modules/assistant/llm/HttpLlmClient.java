package org.tornotron.echno_backend.modules.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;

/**
 * The chat call to an OpenAI-compatible endpoint, in the shape of the compliance module's
 * client: a {@link RestClient} with connect and read timeouts and an optional forward proxy.
 *
 * <p>It is wired, not yet exercised: the fake is what the tests and the local walk run, and this
 * is switched on for real once the endpoint and key are agreed. Nothing here is retried. A failed
 * call is reported as {@link LlmUnavailableException} and the caller says so, because a retry
 * inside a request that a person is waiting on only makes the wait longer.
 */
@Slf4j
@Component
@AssistantModuleEnabled
@ConditionalOnProperty(prefix = "echno.assistant.llm", name = "client", havingValue = "http", matchIfMissing = true)
public class HttpLlmClient implements AssistantLlmClient {

    private final AssistantLlmProperties props;
    private final AssistantProperties assistantProps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpLlmClient(AssistantLlmProperties props, AssistantProperties assistantProps) {
        this.props = props;
        this.assistantProps = assistantProps;
    }

    @Override
    public boolean isConfigured() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    @Override
    public LlmResponse chat(String system, String user) {
        if (!isConfigured()) {
            throw new LlmUnavailableException("The assistant's language model is not configured.");
        }
        String body;
        try {
            body = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(props.getBaseUrl())
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody(system, user))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // The RestClient message can carry the upstream's own response text, which is more of
            // the endpoint than the person asking needs. It goes to the log, not the response.
            log.error("Assistant model call failed: {}", e.getMessage(), e);
            throw new LlmUnavailableException("The assistant's language model could not be reached. Try again in a moment.", e);
        }
        return read(body);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String apiKey = assistantProps.getEmbeddings().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = props.getApiKey();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException("The assistant's embeddings endpoint is not configured.");
        }
        String baseUrl = assistantProps.getEmbeddings().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = props.getBaseUrl();
        }
        String model = assistantProps.getEmbeddings().getModel();
        if (model == null || model.isBlank()) {
            model = "text-embedding-3-small";
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            ArrayNode inputArr = root.putArray("input");
            for (String t : texts) {
                inputArr.add(t);
            }
            String requestJson = objectMapper.writeValueAsString(root);

            String responseBody = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            JsonNode resNode = objectMapper.readTree(responseBody);
            JsonNode dataNode = resNode.path("data");
            if (!dataNode.isArray()) {
                throw new LlmUnavailableException("The embeddings endpoint returned an unexpected response shape.");
            }
            List<float[]> embeddingsList = new ArrayList<>(texts.size());
            for (int i = 0; i < dataNode.size(); i++) {
                JsonNode item = dataNode.get(i);
                JsonNode vecNode = item.path("embedding");
                if (!vecNode.isArray()) {
                    throw new LlmUnavailableException("Embedding item is missing an array embedding vector.");
                }
                float[] vec = new float[vecNode.size()];
                for (int j = 0; j < vecNode.size(); j++) {
                    vec[j] = (float) vecNode.get(j).asDouble();
                }
                embeddingsList.add(vec);
            }
            return embeddingsList;
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant embeddings call failed: {}", e.getMessage(), e);
            throw new LlmUnavailableException("The assistant's embeddings endpoint could not be reached. Try again in a moment.", e);
        }
    }

    private LlmResponse read(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmUnavailableException("The assistant's language model returned an empty answer.");
            }
            JsonNode usage = root.path("usage");
            return new LlmResponse(content, usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0), props.getModel(), props.getModelVersion());
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant model response could not be read: {}", e.getMessage(), e);
            throw new LlmUnavailableException("The assistant's language model returned an answer that could not be read.", e);
        }
    }

    private String requestBody(String system, String user) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("max_tokens", props.getMaxTokens());
        root.put("temperature", props.getTemperature());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return objectMapper.writeValueAsString(root);
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));
        String proxyHost = props.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, props.getProxyPort())));
        }
        return factory;
    }
}
