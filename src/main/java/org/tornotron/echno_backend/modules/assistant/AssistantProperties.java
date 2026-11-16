package org.tornotron.echno_backend.modules.assistant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code echno.assistant.*} settings other than the model's own: how much evidence one
 * question may gather, how long a provider may take, how long a follow-up may lean on the last
 * question, and what a token costs. Every default is the spec's.
 */
@Data
@Component
@AssistantModuleEnabled
@ConfigurationProperties(prefix = "echno.assistant")
public class AssistantProperties {

    private Retrieval retrieval = new Retrieval();

    /** A follow-up may name the previous question only if it is younger than this. */
    private int followupWindowMinutes = 15;

    private Cost cost = new Cost();

    @Data
    public static class Retrieval {

        /** Most evidence units kept from any one provider. */
        private int unitsPerProvider = 20;

        /** Rough token budget for all evidence together, estimated at four characters a token. */
        private int evidenceTokenBudget = 4000;

        /** How long one provider may take before it counts as unavailable. */
        private int providerTimeoutSeconds = 8;

        /** Providers that may run at once. */
        private int maxParallel = 4;
    }

    /** Price per token in millionths of the billing currency; zero records tokens only. */
    @Data
    public static class Cost {

        private long promptTokenMicros = 0;

        private long completionTokenMicros = 0;
    }
}
