package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Where an extract job is. {@code WAITING_LLM} and {@code PAUSED_BUDGET} keep what is done and resume
 * on their own when the reason clears; neither is ever read as "nothing found". {@code PARTIAL} is
 * finished with some chunks unread, which "retry failed parts" reruns. {@code DONE} and
 * {@code FAILED} are final until a person asks for a retry.
 */
public enum ExtractJobStatus {
    QUEUED,
    RUNNING,
    WAITING_LLM,
    PAUSED_BUDGET,
    PARTIAL,
    DONE,
    FAILED
}
