package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How document ingestion reads text through the LLM, bound from
 * {@code echno.modules.document-ingestion.llm}. The endpoint itself (URL, key, model, proxy, timeouts,
 * the {@code enabled} switch) is the shared {@code compliance.ai} block; nothing here can send a call
 * on its own. Design sections 6.4, 7.1, 7.3 and 9.
 */
@Data
@Component
@ConfigurationProperties(prefix = "echno.modules.document-ingestion.llm")
public class IngestLlmProperties {

    /** Completion tokens per call. At about 33 tokens a second this is some 45 s, inside the 60 s read timeout. */
    private int maxTokens = 1500;

    /** Pages per chunk. A truncated answer splits its chunk in half, down to one page. */
    private int chunkPages = 8;

    /** Calls one job sends at once. */
    private int callsPerJob = 4;

    /** Jobs one replica runs at once. */
    private int maxInFlight = 2;

    /** How often each replica looks for work, in milliseconds (read by {@code @Scheduled} too). */
    private long pollIntervalMillis = 5000;

    /** How far ahead a claim holds; moved after every chunk, so it only has to outlast one chunk with its backoff. */
    private Duration leaseDuration = Duration.ofMinutes(10);

    /** Claims before a job whose replica keeps dying is given up on. */
    private int maxAttempts = 3;

    /** Waits before each retry of a call that met a 429, a 5xx or a timeout. Then the chunk is failed. */
    private List<Duration> backoff = List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30));

    /** Sampling temperature; zero reads the same page the same way twice. */
    private double temperature = 0.0;

    /** Ask for {@code response_format: json_object}. Off unless the endpoint is known to honour it; the parser never relies on it. */
    private boolean jsonResponseFormat = false;

    /**
     * Price per million prompt tokens, in US dollars. Zero means the price is unknown: tokens are still
     * recorded and the token budget still applies, and no cost is estimated. The defaults are the
     * figures of design section 7.2; set them for the configured model.
     */
    private BigDecimal inputUsdPerMillion = new BigDecimal("0.25");

    /** Price per million completion tokens, in US dollars. As {@link #inputUsdPerMillion}. */
    private BigDecimal outputUsdPerMillion = new BigDecimal("0.87");

    /** Default budget of one package, in estimated dollars. An admin can raise it per package. */
    private BigDecimal packageBudgetUsd = new BigDecimal("5");

    /** Default budget of one organization per calendar month (UTC), in estimated dollars. */
    private BigDecimal monthlyBudgetUsd = new BigDecimal("50");

    /** Default token budget of one package, which holds when the rates are zero. */
    private long packageBudgetTokens = 5_000_000L;

    /** Default token budget of one organization per calendar month (UTC). */
    private long monthlyBudgetTokens = 50_000_000L;
}
