package org.tornotron.echno_backend.modules.assistant.domain;

/** How a question ended, as the client sees it and as the audit row records it. */
public enum AskOutcome {

    /** An answer with citations. */
    ANSWERED,

    /** The question was not clear enough to act on; the response says what to ask back. */
    CLARIFY,

    /** The assistant could not answer; the response says what was looked for. */
    DECLINED,

    /** Answered, but some sources could not be read; the response names them. */
    DEGRADED,

    /** The language model itself could not be reached or is not configured. */
    UNAVAILABLE
}
