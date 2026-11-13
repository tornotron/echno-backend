package org.tornotron.echno_backend.modules.assistant.api;

/**
 * What asking a provider typically costs, for the planner to weigh and for cost reporting. An
 * estimate the provider's author states, not a measurement.
 *
 * @param typicalLatencyMillis how long a typical retrieval takes
 * @param typicalTokens        model tokens a typical retrieval spends; zero for a provider that
 *                             only reads structured data
 */
public record CostProfile(long typicalLatencyMillis, long typicalTokens) {

    public CostProfile {
        if (typicalLatencyMillis < 0 || typicalTokens < 0) {
            throw new IllegalArgumentException("A cost cannot be negative");
        }
    }
}
