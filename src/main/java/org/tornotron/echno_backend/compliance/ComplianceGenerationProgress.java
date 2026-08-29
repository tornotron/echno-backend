package org.tornotron.echno_backend.compliance;

/**
 * Told how far a generation run has got, once per completed batch.
 *
 * <p>It exists so the slow part of generation can report on itself without knowing anything
 * about jobs, rows or HTTP. The synchronous endpoint passes {@link #NONE} and the numbers go
 * nowhere; the queue passes an implementation that writes them to the job row so a polling
 * caller sees the run advance.
 *
 * <p>What it reports is progress and nothing else. A run that stops at batch three of five
 * has moved this counter three times and still has no result, so nothing downstream may read
 * these numbers as a partial answer. The result is the return of the generation call, and
 * there is no such thing as a partial one.
 */
@FunctionalInterface
public interface ComplianceGenerationProgress {

    /** Reports progress that goes nowhere, for callers that are watching the call itself. */
    ComplianceGenerationProgress NONE = (batchesDone, batchesTotal, rulesAssessed, rulesTotal) -> {
    };

    /**
     * One batch of candidate rules has been assessed.
     *
     * @param batchesDone   batches finished, 1-based and counting up to {@code batchesTotal}
     * @param batchesTotal  batches this run was split into
     * @param rulesAssessed candidate rules covered so far
     * @param rulesTotal    candidate rules in the run
     */
    void batchCompleted(int batchesDone, int batchesTotal, int rulesAssessed, int rulesTotal);
}
