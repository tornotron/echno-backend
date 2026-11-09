package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualificationRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationRequirementType;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.QualificationRequirementEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted qualification requirements to the package's register (design section 4.7), one row
 * per candidate, with provenance on each field. The register is the module's own table and nothing
 * else is written: the requirements are compared with the company profile when the matrix is read.
 * Applying is refused on a package already at stage {@code AWARDED}, where qualification is not shown.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class QualificationRequirementApplier implements IngestionApplier {

    public static final String TARGET = "QUALIFICATION_REQUIREMENT";
    static final int VALUE_MAX = 1000;
    static final int SHORT_MAX = 255;

    private final IngestPackageRepository packages;
    private final SegmentRepository segments;
    private final QualificationRequirementEntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public QualificationRequirementApplier(IngestPackageRepository packages, SegmentRepository segments,
                                           QualificationRequirementEntryRepository entries,
                                           TenantEntityHelper tenantEntityHelper, UserContextService userContextService) {
        this.packages = packages;
        this.segments = segments;
        this.entries = entries;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.QUALIFICATION_REQUIREMENT;
    }

    /** The register is the module's own table, so its guard is the module's apply guard. */
    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return DocumentIngestionModule.APPLY_GUARD;
    }

    @Override
    public void preflight(UUID packageId) {
        requireTender(requirePackage(packageId));
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        QualificationRequirementType type = QualificationRequirementType.ofNaturalKey(candidate.getNaturalKey())
                .orElseThrow(() -> new InvalidRequestException("\"" + candidate.getNaturalKey()
                        + "\" is not one of the qualification requirements"));
        String value = text(accepted.get(QualificationRequirementType.VALUE), VALUE_MAX, "value");
        if (value == null) {
            throw new InvalidRequestException("The accepted " + type.label().toLowerCase(Locale.ROOT) + " requirement is empty");
        }
        String minValue = type.hasMinValue()
                ? text(accepted.get(QualificationRequirementType.MIN_VALUE), SHORT_MAX, "minimum value") : null;
        String period = type.hasPeriod()
                ? text(accepted.get(QualificationRequirementType.PERIOD), SHORT_MAX, "period") : null;
        IngestPackage p = requirePackage(candidate.getPackageId());
        requireTender(p);
        QualificationRequirementEntry row = entries.findByCandidate(candidate.getId()).orElseGet(() -> {
            QualificationRequirementEntry created = new QualificationRequirementEntry();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            created.setCandidateId(candidate.getId());
            return created;
        });
        row.setRequirement(type.name());
        row.setValueText(value);
        row.setMinValueText(minValue);
        row.setPeriodText(period);
        row.setSourceClass(candidate.getSegmentId() == null ? null
                : segments.findByIdScoped(candidate.getSegmentId()).map(s -> s.getDocClass()).orElse(null));
        row.setDocumentId(candidate.getDocumentId());
        row.setUpdatedBy(userContextService.getCurrentUserId());
        QualificationRequirementEntry saved = entries.save(row);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(QualificationRequirementType.VALUE, "value");
        if (minValue != null) {
            fields.put(QualificationRequirementType.MIN_VALUE, "minValue");
        }
        if (period != null) {
            fields.put(QualificationRequirementType.PERIOD, "period");
        }
        return new AppliedTarget(TARGET, saved.getId().toString(), fields);
    }

    private static void requireTender(IngestPackage p) {
        if (p.getStage() != PackageStage.TENDER) {
            throw new InvalidRequestException("The package is at stage " + p.getStage() + "; qualification "
                    + "requirements are kept only while the tender is being bid");
        }
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private static String text(Object value, int max, String what) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        if (s.length() > max) {
            throw new InvalidRequestException("The " + what + " is longer than " + max + " characters; shorten it in "
                    + "the review first");
        }
        return s.isEmpty() ? null : s;
    }
}
