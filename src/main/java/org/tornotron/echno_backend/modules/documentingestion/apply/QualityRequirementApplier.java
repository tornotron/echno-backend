package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.QualityFields;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.QualityRequirementEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted quality requirements to the package's inspection and test plan (design section 4.4),
 * one row per requirement, with provenance on each field. The organization's checklist templates are not
 * touched here: copying a row into one is the separate action of {@link ChecklistCopier}.
 *
 * <p>The activity is matched to one of the organization's inspection trades through the inspections
 * module's port when that module is on for the organization, and only an unambiguous match is kept; with
 * the module off the row is still written, with no trade.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class QualityRequirementApplier implements IngestionApplier {

    public static final String TARGET = "QUALITY_REQUIREMENT";

    /** Payload field to register field, with the register column's length; 0 for a list. */
    private static final Map<String, Column> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put(QualityFields.ACTIVITY, new Column("activity", 255));
        COLUMNS.put(QualityFields.MATERIAL, new Column("material", 255));
        COLUMNS.put(QualityFields.GRADE, new Column("grade", 100));
        COLUMNS.put(QualityFields.STANDARDS, new Column("standards", 0));
        COLUMNS.put(QualityFields.TEST, new Column("test", 500));
        COLUMNS.put(QualityFields.FREQUENCY, new Column("frequency", 1000));
        COLUMNS.put(QualityFields.ACCEPTANCE_CRITERION, new Column("acceptanceCriterion", 1000));
        COLUMNS.put(QualityFields.HOLD_OR_WITNESS_POINT, new Column("holdOrWitnessPoint", 500));
        COLUMNS.put(QualityFields.REQUIRED_DOCUMENTS, new Column("requiredDocuments", 0));
    }

    private record Column(String field, int max) {
    }

    /** The longest entry of a list value. */
    static final int MAX_LIST_ENTRY = 500;
    static final int SUBJECT_MAX = 200;

    private final IngestPackageRepository packages;
    private final SegmentRepository segments;
    private final QualityRequirementEntryRepository entries;
    private final ChecklistTemplatePort checklists;
    private final ModuleRegistry modules;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public QualityRequirementApplier(IngestPackageRepository packages, SegmentRepository segments,
                                     QualityRequirementEntryRepository entries, ChecklistTemplatePort checklists,
                                     ModuleRegistry modules, TenantEntityHelper tenantEntityHelper,
                                     UserContextService userContextService) {
        this.packages = packages;
        this.segments = segments;
        this.entries = entries;
        this.checklists = checklists;
        this.modules = modules;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.QUALITY_REQUIREMENT;
    }

    /** The plan is the module's own table, so its guard is the module's apply guard. */
    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return "@access.can('document-ingestion', 'apply')";
    }

    @Override
    public void preflight(UUID packageId) {
        requirePackage(packageId);
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        Map<String, Object> values = new LinkedHashMap<>();
        COLUMNS.forEach((payloadField, column) -> {
            Object v = column.max() == 0 ? list(payloadField, accepted.get(payloadField))
                    : text(payloadField, accepted.get(payloadField), column.max());
            if (v != null) {
                values.put(payloadField, v);
            }
        });
        // As accepted, so a reviewer's correction counts; the natural key may carry a "#2" suffix.
        String subject = firstText(values, QualityFields.MATERIAL, QualityFields.ACTIVITY, QualityFields.TEST);
        if (subject == null && candidate.getNaturalKey() != null && !candidate.getNaturalKey().isBlank()) {
            subject = candidate.getNaturalKey();
        }
        if (subject == null) {
            throw new InvalidRequestException("The requirement names no material, activity or test");
        }
        IngestPackage p = requirePackage(candidate.getPackageId());
        QualityRequirementEntry row = entries.findByCandidate(candidate.getId()).orElseGet(() -> {
            QualityRequirementEntry created = new QualityRequirementEntry();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            created.setCandidateId(candidate.getId());
            return created;
        });
        row.setProjectId(p.getProjectId());
        row.setSubject(subject.length() <= SUBJECT_MAX ? subject : subject.substring(0, SUBJECT_MAX));
        row.setActivity((String) values.get(QualityFields.ACTIVITY));
        row.setMaterial((String) values.get(QualityFields.MATERIAL));
        row.setGrade((String) values.get(QualityFields.GRADE));
        row.setStandards(castList(values.get(QualityFields.STANDARDS)));
        row.setTest((String) values.get(QualityFields.TEST));
        row.setFrequency((String) values.get(QualityFields.FREQUENCY));
        row.setAcceptanceCriterion((String) values.get(QualityFields.ACCEPTANCE_CRITERION));
        row.setHoldOrWitnessPoint((String) values.get(QualityFields.HOLD_OR_WITNESS_POINT));
        row.setRequiredDocuments(castList(values.get(QualityFields.REQUIRED_DOCUMENTS)));
        Optional<ChecklistTemplatePort.TradeRef> trade = matchTrade(row.getActivity(), row.getMaterial());
        row.setTradeId(trade.map(ChecklistTemplatePort.TradeRef::id).orElse(null));
        row.setTradeCode(trade.map(ChecklistTemplatePort.TradeRef::code).orElse(null));
        row.setTradeName(trade.map(ChecklistTemplatePort.TradeRef::name).orElse(null));
        row.setSourceClass(candidate.getSegmentId() == null ? null
                : segments.findByIdScoped(candidate.getSegmentId()).map(s -> s.getDocClass()).orElse(null));
        row.setDocumentId(candidate.getDocumentId());
        row.setUpdatedBy(userContextService.getCurrentUserId());
        QualityRequirementEntry saved = entries.save(row);
        Map<String, String> fields = new LinkedHashMap<>();
        values.keySet().forEach(k -> fields.put(k, COLUMNS.get(k).field()));
        return new AppliedTarget(TARGET, saved.getId().toString(), fields);
    }

    /** The trade the activity names, when the inspections module is on and exactly one trade matches. */
    private Optional<ChecklistTemplatePort.TradeRef> matchTrade(String activity, String material) {
        if (!modules.isEnabledForOrg(ChecklistTemplatePort.MODULE_ID, TenantContext.getCurrentOrgId())) {
            return Optional.empty();
        }
        return TradeMatcher.match(activity, material, checklists.activeTrades());
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private static String text(String field, Object value, int max) {
        if (value == null) {
            return null;
        }
        String s = value instanceof Collection<?> c ? String.join("; ", c.stream().map(String::valueOf).toList())
                : value.toString();
        s = s.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > max) {
            throw new InvalidRequestException("The " + QualityFields.label(field) + " is longer than " + max
                    + " characters; shorten it in the review first");
        }
        return s;
    }

    /** A list value, from a list or from text a reviewer typed with semicolons between entries. */
    private static List<String> list(String field, Object value) {
        if (value == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        Collection<?> items = value instanceof Collection<?> c ? c : List.of(value.toString().split(";"));
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String s = item.toString().strip();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > MAX_LIST_ENTRY) {
                throw new InvalidRequestException("An entry of the " + QualityFields.label(field) + " is longer than "
                        + MAX_LIST_ENTRY + " characters; shorten it in the review first");
            }
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value == null ? null : new ArrayList<>((List<String>) value);
    }

    private static String firstText(Map<String, Object> values, String... fields) {
        for (String f : fields) {
            if (values.get(f) instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
