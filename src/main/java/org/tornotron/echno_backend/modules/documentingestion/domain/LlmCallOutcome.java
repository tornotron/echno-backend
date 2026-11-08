package org.tornotron.echno_backend.modules.documentingestion.domain;

import org.tornotron.echno_backend.common.llm.ChatCompletionOutcome;

/**
 * How one call in the ledger ended. The transport outcomes are the client's; {@code INVALID_ANSWER}
 * is a complete answer that was not the JSON the prompt asked for. Only {@code OK} means the chunk
 * was read, so only an {@code OK} row lets a retry skip the chunk.
 */
public enum LlmCallOutcome {
    OK,
    INVALID_ANSWER,
    TRUNCATED,
    RATE_LIMITED,
    SERVER_ERROR,
    TIMEOUT,
    UNREACHABLE,
    CLIENT_ERROR,
    MALFORMED_ENVELOPE;

    public static LlmCallOutcome of(ChatCompletionOutcome outcome) {
        return switch (outcome) {
            case OK -> OK;
            case TRUNCATED -> TRUNCATED;
            case RATE_LIMITED -> RATE_LIMITED;
            case SERVER_ERROR -> SERVER_ERROR;
            case TIMEOUT -> TIMEOUT;
            case UNREACHABLE -> UNREACHABLE;
            case MALFORMED_ENVELOPE -> MALFORMED_ENVELOPE;
            case CLIENT_ERROR, NOT_CONFIGURED -> CLIENT_ERROR;
        };
    }
}
