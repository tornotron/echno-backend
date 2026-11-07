package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.Map;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;

/**
 * Writes accepted candidates of one kind to their live table. One bean per kind, in this package.
 *
 * <p>{@link #targetGuard()} is the {@code @PreAuthorize} expression the target's own endpoint
 * carries. Guards in this codebase sit on controllers, so calling the target's service alone would
 * skip them; {@link ApplyService} evaluates this expression for the caller before the first row.
 * The applier then calls the target's service or a module's {@code api} port, never a repository.
 */
public interface IngestionApplier {

    CandidateKind kind();

    String targetGuard();

    /**
     * Writes one candidate and says where it landed. Runs inside the batch transaction, which also
     * writes the provenance; throwing rolls back both.
     *
     * @param candidate The candidate, already accepted
     * @param accepted The accepted payload, with the reviewer's corrections
     */
    AppliedTarget apply(Candidate candidate, Map<String, Object> accepted);

    /**
     * Where an accepted candidate landed.
     *
     * @param targetType Stable name of the target, such as {@code CONTRACT_BOQ_ITEM}
     * @param targetId The target row's id
     * @param fields Payload field to target field, for each field written
     */
    record AppliedTarget(String targetType, String targetId, Map<String, String> fields) {
    }
}
