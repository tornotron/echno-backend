package org.tornotron.echno_backend.common.llm;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Where the OpenAI-compatible chat-completions endpoint is and how to reach it. Every caller of
 * {@link ChatCompletionClient} shares these settings.
 *
 * <p>Bound from the {@code compliance.ai} prefix because compliance was the first caller and
 * owns the deployed configuration. Keeping the prefix means there is one endpoint, one secret
 * ({@code COMPLIANCE_AI_API_KEY}) and one switch: {@code compliance.ai.enabled = false} stops
 * every caller, which is what an operator pressing that switch expects.
 *
 * <p>Compliance's own settings (its token cap, temperature and batching) live in its subclass of
 * this one. This bean is {@link Primary} so that injecting the endpoint settings by this type
 * always gets these, never a module's subclass.
 */
@Data
@Primary
@Component
@ConfigurationProperties(prefix = "compliance.ai")
public class LlmEndpointProperties {

    /** Base URL of the OpenAI-compatible API; {@code /chat/completions} is appended to it. */
    private String baseUrl = "https://inference.do-ai.run/v1";

    /**
     * Secret API key. Empty by default; set via the COMPLIANCE_AI_API_KEY env var. Left out of
     * {@code toString} so that logging these properties cannot print it.
     */
    @ToString.Exclude
    private String apiKey = "";

    /**
     * The name of the outside service behind {@code baseUrl}, as a screen names it to the people whose text
     * it reads. Set it with the URL whenever the endpoint moves to another provider.
     */
    private String providerName = "DigitalOcean hosted AI models (Gradient inference)";

    /** Where the provider states what it does with the text it is sent, or empty when none is known. */
    private String providerPrivacyUrl = "https://docs.digitalocean.com/products/inference/details/data-privacy/";

    /** Model id (slug) to call. */
    private String model = "llama3.3-70b-instruct";

    /**
     * Version label recorded beside the model slug. The slug alone does not say which revision of
     * a hosted model answered; set this when the provider exposes one, or to a date the deployment
     * was pinned. Empty means unknown.
     */
    private String modelVersion = "";

    /** Master switch; when false no caller sends anything and the client reports itself unavailable. */
    private boolean enabled = true;

    /**
     * Optional HTTP forward-proxy host for reaching the endpoint. Empty by default, which means
     * direct egress. Set it where the backend has no direct internet and must go out through a
     * forward proxy.
     */
    private String proxyHost = "";

    /** Proxy port used only when {@code proxyHost} is set. */
    private int proxyPort = 3128;

    /**
     * How long to wait for the TCP connection, in seconds. Where egress goes through a forward
     * proxy the connect is to the proxy, and a loaded proxy can be slower to accept than the
     * endpoint behind it.
     */
    private int connectTimeoutSeconds = 10;

    /**
     * How long to wait for the response after the request is sent, in seconds.
     *
     * <p>Generation runs at roughly 33 completion tokens a second on the configured endpoint, so a
     * caller has to size its {@code max_tokens} against this. Compliance does it with its batch
     * size (a ten-rule batch takes about 20 seconds); a call allowed 1,500 tokens takes about 45.
     */
    private int readTimeoutSeconds = 60;
}
