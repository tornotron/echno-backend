package org.tornotron.echno_backend.modules.assistant.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;

/**
 * The {@code echno.assistant.llm.*} block, the same shape as {@code compliance.ai}. The API key
 * is a secret supplied by the environment and empty by default: the module still starts without
 * one, and {@code ask} answers {@code UNAVAILABLE} until it is set.
 */
@Data
@Component
@AssistantModuleEnabled
@ConfigurationProperties(prefix = "echno.assistant.llm")
public class AssistantLlmProperties {

    /** Which client answers: {@code http} (the default) or {@code fake}, for tests and a local walk. */
    private String client = "http";

    /** Base URL of the OpenAI-compatible API; {@code /chat/completions} is appended to it. */
    private String baseUrl = "https://inference.do-ai.run/v1";

    /** Secret API key. Empty by default. */
    private String apiKey = "";

    private String model = "llama3.3-70b-instruct";

    /** Which revision of the model answered, recorded on every audit row. Empty means unknown. */
    private String modelVersion = "";

    private int maxTokens = 2048;

    private double temperature = 0.1;

    private int connectTimeoutSeconds = 10;

    private int readTimeoutSeconds = 45;

    /** Optional HTTP forward proxy; the lab nodes reach the internet only through one. */
    private String proxyHost = "";

    private int proxyPort = 3128;
}
