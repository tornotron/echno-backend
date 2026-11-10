package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto.Outcome;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto.RowOutcome;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto.TemplateCopy;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.QualityFields;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ProvenanceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.QualityRequirementEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The second, separate action of design section 4.4: copies chosen rows of the inspection and test plan
 * as check points into the organization's checklist template for their trade, through the inspections
 * module's port. Accepting or applying a requirement never touches a template; only this does.
 *
 * <p>Asks what the template endpoints ask ({@link ChecklistTemplatePort#DEFINE_GUARD}), refuses with
 * a sentence when the inspections module is off for the organization, and writes provenance on each check
 * point it adds. A row copied once is not copied again. The template's existing check points are kept as
 * they are and the new ones go at the end, in a new revision; a trade with no template gets one.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ChecklistCopier {

    public static final String TARGET_ITEM = "CHECKLIST_TEMPLATE_ITEM";
    static final String DEFAULT_CATEGORY = "Specification requirements";
    static final int CATEGORY_MAX = 200;
    static final int CHECK_POINT_MAX = 500;
    static final int SPECIFICATION_MAX = 1000;
    static final int EXPECTED_VALUE_MAX = 200;
    static final int TEMPLATE_NAME_MAX = 200;

    /** Payload field to the check point field it lands in, for provenance. */
    private static final Map<String, String> ITEM_FIELDS = new LinkedHashMap<>();

    static {
        ITEM_FIELDS.put(QualityFields.ACTIVITY, "category");
        ITEM_FIELDS.put(QualityFields.MATERIAL, "checkPoint");
        ITEM_FIELDS.put(QualityFields.TEST, "checkPoint");
        ITEM_FIELDS.put(QualityFields.GRADE, "expectedValue");
        ITEM_FIELDS.put(QualityFields.STANDARDS, "specification");
        ITEM_FIELDS.put(QualityFields.FREQUENCY, "specification");
        ITEM_FIELDS.put(QualityFields.HOLD_OR_WITNESS_POINT, "specification");
        ITEM_FIELDS.put(QualityFields.REQUIRED_DOCUMENTS, "specification");
        ITEM_FIELDS.put(QualityFields.ACCEPTANCE_CRITERION, "acceptanceCriterion");
    }

    private final ApplyGuard guard;
    private final ModuleRegistry modules;
    private final ChecklistTemplatePort checklists;
    private final QualityRequirementEntryRepository entries;
    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final ProvenanceRepository provenance;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;

    public ChecklistCopier(ApplyGuard guard, ModuleRegistry modules, ChecklistTemplatePort checklists,
                           QualityRequirementEntryRepository entries, CandidateRepository candidates,
                           SourceRefRepository sources, ProvenanceRepository provenance, TransactionRetryTemplate retry,
                           UserContextService userContextService) {
        this.guard = guard;
        this.modules = modules;
        this.checklists = checklists;
        this.entries = entries;
        this.candidates = candidates;
        this.sources = sources;
        this.provenance = provenance;
        this.retry = retry;
        this.userContextService = userContextService;
    }

    public CopyToTemplateResultDto copy(CopyToTemplateRequest request) {
        ApplyGuard.requireSignedIn();
        guard.require(ChecklistTemplatePort.DEFINE_GUARD);
        if (!modules.isEnabledForOrg(ChecklistTemplatePort.MODULE_ID, TenantContext.getCurrentOrgId())) {
            throw new InvalidRequestException("Site inspections are not enabled for this organization, so the "
                    + "requirements cannot be copied into a checklist template. They stay in the inspection and test "
                    + "plan.");
        }
        if (request == null || request.requirementIds() == null || request.requirementIds().isEmpty()) {
            throw new InvalidRequestException("Name at least one row of the inspection and test plan to copy");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(request.requirementIds()));
        Long copiedBy = userContextService.getCurrentUserId();
        return retry.execute("DocumentIngestion.copyToChecklist", () -> copyAll(ids, request.tradeId(), copiedBy));
    }

    private CopyToTemplateResultDto copyAll(List<UUID> ids, UUID tradeOverride, Long copiedBy) {
        ChecklistTemplatePort.TradeRef override = tradeOverride == null ? null : checklists.findTrade(tradeOverride)
                .orElseThrow(() -> new ResourceNotFoundException("Trade " + tradeOverride
                        + " is not a trade of this organization"));
        Map<UUID, QualityRequirementEntry> byId = entries.findByIdsScoped(ids).stream()
                .collect(Collectors.toMap(QualityRequirementEntry::getId, Function.identity()));
        Map<UUID, RowOutcome> outcomes = new LinkedHashMap<>();
        Map<UUID, List<QualityRequirementEntry>> byTrade = new LinkedHashMap<>();
        Map<UUID, String> tradeNames = new LinkedHashMap<>();
        for (UUID id : ids) {
            QualityRequirementEntry row = byId.get(id);
            if (row == null) {
                outcomes.put(id, new RowOutcome(id, Outcome.NOT_FOUND, null, null,
                        "No such row of the inspection and test plan"));
                continue;
            }
            if (row.getTemplateItemId() != null) {
                outcomes.put(id, new RowOutcome(id, Outcome.ALREADY_COPIED, row.getTemplateId(), row.getTemplateItemId(),
                        "Already copied into a checklist template; edit it there"));
                continue;
            }
            UUID tradeId = override != null ? override.id() : row.getTradeId();
            if (tradeId == null) {
                outcomes.put(id, new RowOutcome(id, Outcome.NO_TRADE, null, null,
                        "The activity was not matched to a trade; choose the trade to copy it into"));
                continue;
            }
            byTrade.computeIfAbsent(tradeId, k -> new ArrayList<>()).add(row);
            tradeNames.putIfAbsent(tradeId, override != null ? override.name() : row.getTradeName());
            outcomes.put(id, null);
        }

        List<TemplateCopy> templates = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<UUID, List<QualityRequirementEntry>> group : byTrade.entrySet()) {
            UUID tradeId = group.getKey();
            String tradeName = tradeNames.get(tradeId) != null ? tradeNames.get(tradeId)
                    : checklists.findTrade(tradeId).map(ChecklistTemplatePort.TradeRef::name).orElse("Trade");
            List<ChecklistTemplatePort.TemplateItem> items = group.getValue().stream().map(ChecklistCopier::item).toList();
            ChecklistTemplatePort.TemplateRef template = checklists.appendItems(tradeId,
                    cut(tradeName + " checklist", TEMPLATE_NAME_MAX), items);
            for (int i = 0; i < group.getValue().size(); i++) {
                QualityRequirementEntry row = group.getValue().get(i);
                UUID itemId = template.addedItemIds().get(i);
                row.setTemplateId(template.id());
                row.setTemplateItemId(itemId);
                row.setTemplateVersion(template.version());
                row.setCopiedBy(copiedBy);
                row.setCopiedAt(now);
                entries.save(row);
                writeProvenance(row, itemId, copiedBy, now);
                outcomes.put(row.getId(), new RowOutcome(row.getId(), Outcome.COPIED, template.id(), itemId, null));
            }
            templates.add(new TemplateCopy(template.id(), tradeId, tradeName, template.name(), template.version(),
                    template.created(), template.active(), group.getValue().size()));
        }
        return new CopyToTemplateResultDto(templates, new ArrayList<>(outcomes.values()));
    }

    /**
     * A check point from a row, each value as the plan holds it: the test, or the material, with its grade
     * as the expected value; the standards, frequency, hold or witness point and documents as the
     * specification; the acceptance criterion only as printed, so a row with none adds a check point with
     * none. A hold point makes the check point high priority.
     */
    static ChecklistTemplatePort.TemplateItem item(QualityRequirementEntry row) {
        String category = firstOf(row.getActivity(), row.getTradeName(), DEFAULT_CATEGORY);
        String subject = row.getMaterial() != null ? row.getMaterial() : row.getSubject();
        String checkPoint = row.getTest() != null
                ? row.getTest() + (subject == null || subject.equals(row.getTest()) ? "" : ": " + subject)
                : subject;
        List<String> spec = new ArrayList<>();
        if (row.getStandards() != null && !row.getStandards().isEmpty()) {
            spec.add("Standards: " + String.join(", ", row.getStandards()));
        }
        if (row.getFrequency() != null) {
            spec.add("Frequency: " + row.getFrequency());
        }
        if (row.getHoldOrWitnessPoint() != null) {
            spec.add("Hold or witness point: " + row.getHoldOrWitnessPoint());
        }
        if (row.getRequiredDocuments() != null && !row.getRequiredDocuments().isEmpty()) {
            spec.add("Documents: " + String.join(", ", row.getRequiredDocuments()));
        }
        String specification = spec.isEmpty() ? null : String.join(". ", spec);
        if (specification != null && specification.length() > SPECIFICATION_MAX) {
            throw new InvalidRequestException("The standards, frequency, hold point and documents of \"" + row.getSubject()
                    + "\" together run over " + SPECIFICATION_MAX + " characters, the template's limit; shorten them in "
                    + "the template after copying the others");
        }
        if (row.getGrade() != null && row.getGrade().length() > EXPECTED_VALUE_MAX) {
            throw new InvalidRequestException("The grade of \"" + row.getSubject() + "\" is longer than the template's "
                    + EXPECTED_VALUE_MAX + " characters");
        }
        String priority = row.getHoldOrWitnessPoint() != null ? "high" : "medium";
        return new ChecklistTemplatePort.TemplateItem(cut(category, CATEGORY_MAX), cut(checkPoint, CHECK_POINT_MAX),
                specification, row.getGrade(), row.getAcceptanceCriterion(), null, false, priority);
    }

    private void writeProvenance(QualityRequirementEntry row, UUID itemId, Long copiedBy, LocalDateTime now) {
        Candidate c = candidates.findByIdScoped(row.getCandidateId()).orElse(null);
        if (c == null) {
            return;
        }
        Map<String, Object> accepted = c.getAcceptedPayload() == null ? c.getPayload() : c.getAcceptedPayload();
        Map<String, SourceRef> refByField = sources.findForCandidates(List.of(c.getId())).stream()
                .collect(Collectors.toMap(SourceRef::getField, Function.identity(), (first, second) -> first));
        ITEM_FIELDS.forEach((payloadField, itemField) -> {
            if (accepted.get(payloadField) == null) {
                return;
            }
            SourceRef ref = refByField.get(payloadField);
            Provenance p = new Provenance();
            p.setOrganization(c.getOrganization());
            p.setTargetType(TARGET_ITEM);
            p.setTargetId(itemId.toString());
            p.setTargetField(itemField);
            p.setValueExtracted(text(c.getPayload().get(payloadField)));
            p.setValueAccepted(text(accepted.get(payloadField)));
            p.setSourceRefId(ref == null ? null : ref.getId());
            p.setEnteredByHand(ref == null && c.getPayload().get(payloadField) == null);
            p.setCandidateId(c.getId());
            p.setAcceptedBy(c.getReviewedBy());
            p.setAcceptedAt(c.getReviewedAt());
            p.setAppliedBy(copiedBy);
            p.setAppliedAt(now);
            provenance.save(p);
        });
    }

    private static String firstOf(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        if (value instanceof Collection<?> c) {
            return c.stream().map(Objects::toString).collect(Collectors.joining(", "));
        }
        return value == null ? null : Objects.toString(value);
    }
}
