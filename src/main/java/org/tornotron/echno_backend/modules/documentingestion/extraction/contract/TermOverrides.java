package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;

/**
 * Where the special conditions or the contract data restate a term of the general conditions, both are
 * shown and the special one is proposed (design section 4.5). The general one is flagged
 * {@value #OVERRIDDEN_BY_SCC}, so a bulk accept passes it by, and the special one points at it through
 * {@code supersedes_candidate_id}, so accepting the special one supersedes it through the review path.
 *
 * <p>Pure: it reads rows and says what to change. Rows a person has decided on keep their flags.
 */
public final class TermOverrides {

    public static final String OVERRIDDEN_BY_SCC = "OVERRIDDEN_BY_SCC";

    private TermOverrides() {
    }

    /**
     * One contract term candidate as the linker sees it.
     *
     * @param docClass The class of the segment it was read from
     * @param clause   The clause as proposed, or null
     */
    public record Row(UUID id, String term, DocClass docClass, CandidateStatus status, String clause,
                      List<CandidateFlag> flags, UUID supersedesCandidateId, LocalDateTime createdAt) {
    }

    /**
     * What to change on one row.
     *
     * @param addFlag    A flag to add, or null
     * @param supersedes The candidate it now supersedes, or null for no change
     */
    public record Change(CandidateFlag addFlag, UUID supersedes) {
    }

    /** The changes, by candidate id. */
    public static Map<UUID, Change> decide(List<Row> rows) {
        Map<UUID, Change> changes = new LinkedHashMap<>();
        Map<String, List<Row>> byTerm = rows.stream().filter(r -> r.term() != null && live(r.status()))
                .collect(Collectors.groupingBy(Row::term, LinkedHashMap::new, Collectors.toList()));
        for (List<Row> group : byTerm.values()) {
            List<Row> generals = group.stream().filter(r -> r.docClass() == DocClass.GCC).toList();
            List<Row> specials = group.stream().filter(r -> isSpecial(r.docClass())).toList();
            if (generals.isEmpty() || specials.isEmpty()) {
                continue;
            }
            Row general = lead(generals).orElseThrow();
            Row special = lead(specials).orElseThrow();
            CandidateFlag flag = flag(special);
            for (Row g : generals) {
                if (g.status() == CandidateStatus.PROPOSED
                        && g.flags().stream().noneMatch(f -> f.code().equals(OVERRIDDEN_BY_SCC))) {
                    changes.put(g.id(), new Change(flag, null));
                }
            }
            for (Row s : specials) {
                if (s.status() == CandidateStatus.PROPOSED && s.supersedesCandidateId() == null) {
                    changes.put(s.id(), new Change(null, general.id()));
                }
            }
        }
        return changes;
    }

    static boolean isSpecial(DocClass docClass) {
        return docClass == DocClass.SCC || docClass == DocClass.CONTRACT_DATA;
    }

    private static boolean live(CandidateStatus status) {
        return status != CandidateStatus.REJECTED && status != CandidateStatus.SUPERSEDED;
    }

    /** The row that stands for its side: applied first, then accepted, then the latest proposed. */
    private static Optional<Row> lead(List<Row> rows) {
        return rows.stream().max(Comparator.comparingInt((Row r) -> rank(r.status()))
                .thenComparing(Row::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Row::id));
    }

    private static int rank(CandidateStatus status) {
        return switch (status) {
            case APPLIED -> 3;
            case ACCEPTED -> 2;
            case PROPOSED, APPLY_FAILED -> 1;
            default -> 0;
        };
    }

    private static CandidateFlag flag(Row special) {
        String where = special.docClass() == DocClass.CONTRACT_DATA ? "The contract data restate"
                : "The special conditions restate";
        String clause = special.clause() == null ? "" : " in clause " + special.clause();
        return new CandidateFlag(OVERRIDDEN_BY_SCC, where + " this term" + clause + ", and their value is the one "
                + "proposed. Accept this one only if they do not apply.");
    }
}
