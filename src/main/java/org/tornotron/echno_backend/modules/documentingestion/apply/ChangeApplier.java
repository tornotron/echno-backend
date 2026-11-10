package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.ChangeMatcher;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ProvenanceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;

/**
 * Applies accepted changes (design section 4.8). A change to a value already on a live BOQ line updates
 * that line through the Work Progress port, but only while the line still holds the value last applied to
 * it: a line edited by hand since then is never overwritten silently. The row fails with all three values
 * named, and a person chooses (the review package's {@code ConflictService}). A change to a value not yet
 * applied proposes the revised value as a new candidate that supersedes the earlier one, for review like any
 * other; a second change to the same row builds on the revision the first one proposed, so the row under
 * review carries both. A change that matched nothing, or an item added or removed, is entered by hand.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ChangeApplier implements IngestionApplier {

    public static final String TARGET_CANDIDATE = "CANDIDATE";

    /** On an accepted change: the person chose to write it over a record edited by hand. */
    public static final String OVERRIDE_RECORD = "overrideRecord";

    /**
     * With {@link #OVERRIDE_RECORD}: the value on the record when the person chose. The choice holds only
     * while the record still has it; a later edit needs a new choice.
     */
    public static final String OVERRIDE_RECORD_VALUE = "overrideRecordValue";

    /** How far a chain of revisions is followed; one per change to the row. */
    private static final int MAX_CHAIN = 100;

    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final ProvenanceRepository provenance;
    private final CandidateWriter writer;
    private final ContractBoqPort contractBoq;
    private final ModuleRegistry modules;

    public ChangeApplier(CandidateRepository candidates, SourceRefRepository sources, ProvenanceRepository provenance,
                         CandidateWriter writer, ContractBoqPort contractBoq, ModuleRegistry modules) {
        this.candidates = candidates;
        this.sources = sources;
        this.provenance = provenance;
        this.writer = writer;
        this.contractBoq = contractBoq;
        this.modules = modules;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.CHANGE;
    }

    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return ContractBoqPort.WRITE_GUARD;
    }

    @Override
    public AppliedTarget apply(Candidate change, Map<String, Object> accepted) {
        if (hasFlag(change, ChangeMatcher.ITEM_ADDED) || hasFlag(change, ChangeMatcher.ITEM_REMOVED)) {
            throw new InvalidRequestException("An item added or removed by a revision is entered on the contract by "
                    + "hand; this record stays as the note of it.");
        }
        if (change.getSupersedesCandidateId() == null) {
            throw new InvalidRequestException("This change matches no earlier value, so there is nothing to apply it to. "
                    + "Enter it by hand.");
        }
        Candidate prior = candidates.findByIdScoped(change.getSupersedesCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException("The value this change replaces no longer exists"));
        String field = text(accepted.get(ChangeMatcher.FIELD));
        String newValue = text(accepted.get(ChangeMatcher.NEW_VALUE));
        if (newValue == null) {
            throw new InvalidRequestException("The change has no new value");
        }
        String payloadField = ChangeMatcher.payloadField(prior.getKind(), field);
        if (payloadField == null) {
            throw new InvalidRequestException("The change does not say which field it changes. Set it in the review.");
        }
        Candidate head = head(prior);
        if (head.getAppliedTargetId() == null) {
            return proposeRevised(change, prior, head, payloadField, newValue);
        }
        if (isBoqLine(head)) {
            return updateBoqLine(head, BoqField.ofKey(payloadField).orElseThrow(() -> new InvalidRequestException(
                    "A BOQ line has no field \"" + field + "\"")), newValue, accepted);
        }
        throw new InvalidRequestException("Changing a " + prior.getKind() + " already on a live record is not built yet. "
                + "Change the record by hand.");
    }

    /**
     * The value the live record holds now for the field a change names, or null when the change is not to a
     * live BOQ line. Read when a person chooses to write a change over a record edited by hand, so the
     * choice is bound to the value they were shown.
     */
    public String recordValue(Candidate change) {
        if (change.getSupersedesCandidateId() == null) {
            return null;
        }
        Candidate prior = candidates.findByIdScoped(change.getSupersedesCandidateId()).orElse(null);
        if (prior == null) {
            return null;
        }
        String payloadField = ChangeMatcher.payloadField(prior.getKind(),
                text(ChangeMatcher.values(change).get(ChangeMatcher.FIELD)));
        Candidate head = head(prior);
        BoqField field = payloadField == null ? null : BoqField.ofKey(payloadField).orElse(null);
        if (field == null || head.getAppliedTargetId() == null || !isBoqLine(head)) {
            return null;
        }
        return contractBoq.findItem(UUID.fromString(head.getAppliedTargetId())).map(item -> current(item, field)).orElse(null);
    }

    /**
     * The row now in force for a prior: the prior itself, or the latest revision proposed from it by an
     * earlier change and not rejected, followed along the chain.
     */
    private Candidate head(Candidate prior) {
        Candidate head = prior;
        for (int i = 0; i < MAX_CHAIN; i++) {
            Candidate next = null;
            for (Candidate c : candidates.findSuccessors(head.getId(), head.getKind())) {
                if (c.getStatus() != CandidateStatus.REJECTED) {
                    next = c;
                }
            }
            if (next == null) {
                return head;
            }
            head = next;
        }
        return head;
    }

    private static boolean isBoqLine(Candidate c) {
        return c.getKind() == CandidateKind.BOQ_ITEM && BoqItemApplier.TARGET_TYPE.equals(c.getAppliedTargetType());
    }

    private AppliedTarget updateBoqLine(Candidate prior, BoqField field, String newValue, Map<String, Object> accepted) {
        if (field == BoqField.AMOUNT) {
            throw new InvalidRequestException("The amount of a BOQ line is worked out from its quantity and rate, so a "
                    + "change to the amount is not applied. Change the quantity or the rate instead.");
        }
        if (!modules.isEnabledForOrg(ContractBoqPort.MODULE_ID, TenantContext.getCurrentOrgId())) {
            throw new InvalidRequestException("Contract billing is not enabled for this organization, so the BOQ line "
                    + "cannot be changed.");
        }
        UUID itemId = UUID.fromString(prior.getAppliedTargetId());
        ContractBoqPort.BoqItemRef item = contractBoq.findItem(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("The BOQ line " + itemId + " no longer exists"));
        String targetField = targetField(field);
        String current = current(item, field);
        String lastApplied = lastApplied(prior, itemId, targetField, field);
        String chosenOver = text(accepted.get(OVERRIDE_RECORD_VALUE));
        boolean override = Boolean.TRUE.equals(accepted.get(OVERRIDE_RECORD))
                && (chosenOver == null || current == null || ChangeMatcher.sameValue(field, current, chosenOver));
        if (!override && lastApplied != null && current != null && !ChangeMatcher.sameValue(field, current, lastApplied)) {
            throw new ApplyConflictException("The BOQ line " + item.itemCode() + " was edited by hand after it was "
                    + "applied. Its " + field.key() + " is " + current + " on the record, " + lastApplied + " was applied, "
                    + "and the change gives " + newValue + ". Nothing was overwritten; choose which to keep.",
                    current, lastApplied);
        }
        ContractBoqPort.BoqLine line = new ContractBoqPort.BoqLine(
                field == BoqField.ITEM_CODE ? newValue : item.itemCode(),
                field == BoqField.DESCRIPTION ? newValue : item.description(),
                field == BoqField.UNIT ? newValue : item.unit(),
                field == BoqField.QUANTITY ? number(newValue, "quantity") : item.quantity(),
                field == BoqField.RATE ? number(newValue, "rate") : item.rate(),
                null);
        contractBoq.updateItem(itemId, line);
        return new AppliedTarget(BoqItemApplier.TARGET_TYPE, itemId.toString(), Map.of(ChangeMatcher.NEW_VALUE, targetField));
    }

    /**
     * The row in force carries on as a new proposal with the field changed, for a person to review and apply.
     * A revision an earlier change proposed and nobody has applied is replaced by this one, which holds both
     * changes, so the row under review is never missing one of them.
     */
    private AppliedTarget proposeRevised(Candidate change, Candidate prior, Candidate head, String payloadField,
                                         String newValue) {
        Map<String, Object> payload = new LinkedHashMap<>(ChangeMatcher.values(head));
        payload.put(payloadField, newValue);
        List<ProposedCandidate.ProposedSource> refs = new ArrayList<>();
        for (SourceRef s : sources.findForCandidates(List.of(head.getId()))) {
            if (!s.getField().equals(payloadField)) {
                refs.add(source(s, s.getField()));
            }
        }
        for (SourceRef s : sources.findForCandidates(List.of(change.getId()))) {
            if (s.getField().equals(ChangeMatcher.NEW_VALUE)) {
                refs.add(source(s, payloadField));
            }
        }
        List<CandidateFlag> flags = new ArrayList<>(head.getFlags());
        flags.add(new CandidateFlag("REVISED", "Revised by a change: " + payloadField + " is now " + newValue + "."));
        UUID revised = writer.propose(new ProposedCandidate(head.getPackageId(), change.getDocumentId(),
                change.getSegmentId(), head.getKind(), head.getNaturalKey(), payload, flags, change.getMethod(), refs,
                head.getId()));
        if (!head.getId().equals(prior.getId()) && head.getStatus() != CandidateStatus.SUPERSEDED) {
            head.setStatus(CandidateStatus.SUPERSEDED);
            candidates.save(head);
        }
        return new AppliedTarget(TARGET_CANDIDATE, revised.toString(), Map.of());
    }

    private String lastApplied(Candidate prior, UUID itemId, String targetField, BoqField field) {
        List<Provenance> latest = provenance.findLatestForField(BoqItemApplier.TARGET_TYPE, itemId.toString(), targetField,
                PageRequest.of(0, 1));
        if (!latest.isEmpty()) {
            return latest.get(0).getValueAccepted();
        }
        return text(ChangeMatcher.values(prior).get(field.key()));
    }

    static String targetField(BoqField field) {
        return switch (field) {
            case QUANTITY -> "contractQuantity";
            case ITEM_CODE -> "itemCode";
            default -> field.key();
        };
    }

    private static String current(ContractBoqPort.BoqItemRef item, BoqField field) {
        Object v = switch (field) {
            case ITEM_CODE -> item.itemCode();
            case DESCRIPTION -> item.description();
            case UNIT -> item.unit();
            case QUANTITY -> item.quantity();
            case RATE -> item.rate();
            case AMOUNT -> null;
        };
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        return v == null ? null : v.toString();
    }

    /** The whole value read as a measure, such as {@code 920 cum}; a value only partly a number is refused. */
    static BigDecimal number(String text, String what) {
        BigDecimal value = ChangeMatcher.measure(text);
        if (value != null) {
            return value;
        }
        throw new InvalidRequestException("The new " + what + " \"" + text + "\" is not a number. Correct it in the review.");
    }

    private static ProposedCandidate.ProposedSource source(SourceRef s, String field) {
        return new ProposedCandidate.ProposedSource(field, s.getDocumentId(), s.getDocumentSha256(), s.getPage(), s.getBox(),
                s.getSheet(), s.getRowNumber(), s.getCell(), s.getParagraph(), s.getQuote(), s.isQuoteVerified());
    }

    private static boolean hasFlag(Candidate c, String code) {
        return c.getFlags().stream().anyMatch(f -> f.code().equals(code));
    }

    private static String text(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
