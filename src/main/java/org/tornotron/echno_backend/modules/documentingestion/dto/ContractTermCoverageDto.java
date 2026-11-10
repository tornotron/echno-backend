package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Each of the 19 contract terms of a package, found or not (design section 4.5): a term found lists every
 * value read for it, with the special conditions' one proposed over the general conditions'; a term not
 * found says which pages were searched.
 */
@Schema(description = "The 19 contract terms of a package, each found with its values and clauses, or not found "
        + "with the pages searched.")
public record ContractTermCoverageDto(
        @Schema(description = "A sentence the screen shows with the terms and obligations.")
        String notice,
        @Schema(description = "The parts of the documents read for contract terms: one per confirmed GCC, SCC or "
                + "contract data segment, with its latest reading.")
        List<ReadPart> read,
        @Schema(description = "The 19 terms, in a fixed order.")
        List<TermCoverage> terms) {

    /** Whether a term was found in what was read. */
    public enum Status {
        FOUND, NOT_FOUND, NOT_READ
    }

    @Schema(description = "One part of the documents read for contract terms.")
    public record ReadPart(UUID documentId, String fileName, UUID segmentId,
                           @Schema(example = "GCC") String docClass,
                           @Schema(description = "The latest reading's status.", example = "DONE") String status,
                           @Schema(nullable = true, description = "The pages read, such as \"1 to 8, 11 to 40\".",
                                   example = "1 to 40") String pagesSearched,
                           @Schema(description = "Pages of the part that were not read, such as a page not in "
                                   + "English or one whose reading failed.") List<Integer> pagesNotRead) {
    }

    @Schema(description = "One of the 19 terms.")
    public record TermCoverage(
            @Schema(example = "ld_rate") String term,
            @Schema(example = "LD rate") String label,
            Status status,
            @Schema(nullable = true, description = "For a term not found, \"Not found, pages searched: ...\"; for "
                    + "one not read yet, why.", example = "Not found, pages searched: 1 to 40 of Conditions.pdf")
            String message,
            @Schema(nullable = true, description = "The value proposed among those found: the special conditions' or "
                    + "the contract data's over the general conditions'.")
            UUID proposedCandidateId,
            List<FoundValue> found) {
    }

    @Schema(description = "One value read for a term.")
    public record FoundValue(UUID candidateId,
                             @Schema(example = "SCC") String docClass,
                             String value,
                             @Schema(nullable = true, example = "47.1") String clause,
                             @Schema(example = "PROPOSED") String status,
                             @Schema(description = "True on a general conditions' value that the special conditions "
                                     + "or contract data restate.") boolean overridden,
                             @Schema(nullable = true, description = "The value this one replaces, for a special "
                                     + "conditions' value.") UUID supersedesCandidateId) {
    }
}
