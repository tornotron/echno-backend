package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.TenderProfile;
import org.tornotron.echno_backend.modules.documentingestion.extraction.project.ProjectField;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.TenderProfileRepository;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted project fields (design section 4.1). The name and location go to the {@code Project}:
 * a package with no project creates one through the normal create service, as {@code upcoming}, so the
 * compliance run still waits for its approval; a package on a project updates that project's fields.
 * Every other field goes to the package's tender profile. Each field keeps its provenance.
 *
 * <p>One value per field is applied: two accepted values for the same field are refused before any row,
 * so a person rejects one first.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ProjectFieldApplier implements IngestionApplier {

    public static final String TARGET_PROJECT = "PROJECT";
    public static final String TARGET_PROFILE = "TENDER_PROFILE";
    /** The guard on {@code POST /api/v1/projects} and {@code PATCH /api/v1/projects/{id}}, both forms. */
    public static final String PROJECT_WRITE_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";
    /** The project form's limits, which the update path does not check on its own. */
    static final int NAME_MIN = 3;
    static final int NAME_MAX = 50;
    static final int ADDRESS_MIN = 3;
    static final int ADDRESS_MAX = 255;
    static final int PROFILE_TEXT_MAX = 500;
    static final int SCOPE_MAX = 4000;
    static final int MAX_FIELDS = 500;

    private final IngestPackageRepository packages;
    private final CandidateRepository candidates;
    private final TenderProfileRepository profiles;
    private final ProjectService projects;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public ProjectFieldApplier(IngestPackageRepository packages, CandidateRepository candidates,
                               TenderProfileRepository profiles, ProjectService projects,
                               TenantEntityHelper tenantEntityHelper, UserContextService userContextService) {
        this.packages = packages;
        this.candidates = candidates;
        this.profiles = profiles;
        this.projects = projects;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.PROJECT_FIELD;
    }

    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return PROJECT_WRITE_GUARD;
    }

    @Override
    public void preflight(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        Map<String, Candidate> accepted = new HashMap<>();
        for (Candidate c : candidates.findForReview(packageId, CandidateKind.PROJECT_FIELD, CandidateStatus.ACCEPTED,
                null, PageRequest.of(0, MAX_FIELDS))) {
            ProjectField field = ProjectField.of(c.getNaturalKey()).orElseThrow(() -> new InvalidRequestException(
                    "\"" + c.getNaturalKey() + "\" is not a project field"));
            if (accepted.put(field.key(), c) != null) {
                throw new InvalidRequestException("Two values are accepted for " + label(field)
                        + ". Reject one of them first.");
            }
        }
        if (p.getProjectId() == null && (accepted.containsKey(ProjectField.PROJECT_NAME.key())
                || accepted.containsKey(ProjectField.LOCATION.key()))
                && !(hasValue(ProjectField.PROJECT_NAME, packageId) && hasValue(ProjectField.LOCATION, packageId))) {
            throw new InvalidRequestException("To create the project, accept both its name and its location.");
        }
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        ProjectField field = ProjectField.of(candidate.getNaturalKey()).orElseThrow(() -> new InvalidRequestException(
                "\"" + candidate.getNaturalKey() + "\" is not a project field"));
        String value = text(accepted.get(ProjectField.VALUE));
        if (value == null) {
            throw new InvalidRequestException("The accepted " + label(field) + " is empty");
        }
        IngestPackage p = requirePackage(candidate.getPackageId());
        if (field.target() == ProjectField.Target.PROJECT) {
            return applyToProject(p, field, value);
        }
        return applyToProfile(p, field, value);
    }

    private AppliedTarget applyToProject(IngestPackage p, ProjectField field, String value) {
        String projectField = field == ProjectField.PROJECT_NAME ? "projectName" : "projectAddress";
        if (field == ProjectField.PROJECT_NAME) {
            requireLength(value, NAME_MIN, NAME_MAX, "A project name");
        } else {
            requireLength(value, ADDRESS_MIN, ADDRESS_MAX, "A project location");
        }
        if (p.getProjectId() == null) {
            ProjectCreationDto dto = new ProjectCreationDto();
            String name = field == ProjectField.PROJECT_NAME ? value : otherValue(p.getId(), ProjectField.PROJECT_NAME);
            String address = field == ProjectField.LOCATION ? value : otherValue(p.getId(), ProjectField.LOCATION);
            requireLength(name, NAME_MIN, NAME_MAX, "A project name");
            requireLength(address, ADDRESS_MIN, ADDRESS_MAX, "A project location");
            dto.setProjectName(name);
            dto.setProjectAddress(address);
            ProjectSimpleDto created = projects.addProject(dto, null);
            p.setProjectId(created.getId());
            packages.save(p);
            profiles.findByPackage(p.getId()).ifPresent(t -> {
                t.setProjectId(created.getId());
                profiles.save(t);
            });
        } else {
            projects.partialUpdateAProject(new HashMap<>(Map.of(projectField, value)), p.getProjectId(), null, null);
        }
        return new AppliedTarget(TARGET_PROJECT, p.getProjectId().toString(), Map.of(ProjectField.VALUE, projectField));
    }

    private AppliedTarget applyToProfile(IngestPackage p, ProjectField field, String value) {
        TenderProfile t = profiles.findByPackage(p.getId()).orElseGet(() -> {
            TenderProfile created = new TenderProfile();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            return created;
        });
        t.setProjectId(p.getProjectId());
        String column = switch (field) {
            case CLIENT -> {
                t.setClient(fit(field, value));
                yield "client";
            }
            case ESTIMATED_VALUE -> {
                t.setEstimatedValueText(fit(field, value));
                t.setEstimatedValue(TenderValues.amount(value).orElse(null));
                yield "estimatedValue";
            }
            case EMD -> {
                t.setEmdText(fit(field, value));
                t.setEmdAmount(TenderValues.amount(value).orElse(null));
                yield "emdAmount";
            }
            case TENDER_FEE -> {
                t.setTenderFeeText(fit(field, value));
                t.setTenderFeeAmount(TenderValues.amount(value).orElse(null));
                yield "tenderFeeAmount";
            }
            case BID_DUE_DATE -> {
                t.setBidDueText(fit(field, value));
                t.setBidDueAt(TenderValues.dateTime(value).orElse(null));
                yield "bidDueAt";
            }
            case BID_VALIDITY -> {
                t.setBidValidityText(fit(field, value));
                t.setBidValidityDays(TenderValues.days(value).orElse(null));
                yield "bidValidityDays";
            }
            case DURATION -> {
                t.setDurationText(fit(field, value));
                yield "durationText";
            }
            case CONSULTANT -> {
                t.setConsultant(fit(field, value));
                yield "consultant";
            }
            case ENGINEER -> {
                t.setEngineer(fit(field, value));
                yield "engineer";
            }
            case SCOPE -> {
                if (value.length() > SCOPE_MAX) {
                    throw new InvalidRequestException("The scope is longer than " + SCOPE_MAX + " characters; shorten "
                            + "it in the review first");
                }
                t.setScope(value);
                yield "scope";
            }
            default -> throw new IllegalStateException(field + " is a project field");
        };
        t.setUpdatedBy(userContextService.getCurrentUserId());
        TenderProfile saved = profiles.save(t);
        return new AppliedTarget(TARGET_PROFILE, saved.getId().toString(), Map.of(ProjectField.VALUE, column));
    }

    private boolean hasValue(ProjectField field, UUID packageId) {
        return acceptedOrApplied(packageId, field).isPresent();
    }

    private String otherValue(UUID packageId, ProjectField field) {
        return acceptedOrApplied(packageId, field).orElseThrow(() -> new InvalidRequestException(
                "To create the project, accept both its name and its location."));
    }

    /** The accepted or applied value of a field, with the reviewer's correction. */
    private Optional<String> acceptedOrApplied(UUID packageId, ProjectField field) {
        for (CandidateStatus status : List.of(CandidateStatus.ACCEPTED, CandidateStatus.APPLIED)) {
            for (Candidate c : candidates.findForReview(packageId, CandidateKind.PROJECT_FIELD, status, null,
                    PageRequest.of(0, MAX_FIELDS))) {
                if (field.key().equals(c.getNaturalKey())) {
                    Map<String, Object> payload = c.getAcceptedPayload() == null ? c.getPayload() : c.getAcceptedPayload();
                    String v = text(payload.get(ProjectField.VALUE));
                    if (v != null) {
                        return Optional.of(v);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private static void requireLength(String value, int min, int max, String what) {
        if (value.length() < min || value.length() > max) {
            throw new InvalidRequestException(what + " must be " + min + " to " + max + " characters; \"" + value
                    + "\" is " + value.length() + ". Correct it in the review first.");
        }
    }

    private static String label(ProjectField field) {
        return field.key().replace('_', ' ');
    }

    /** The value as accepted; one longer than its column is refused, never cut, so the profile holds what provenance says. */
    private static String fit(ProjectField field, String value) {
        if (value.length() > PROFILE_TEXT_MAX) {
            throw new InvalidRequestException("The " + label(field) + " is longer than " + PROFILE_TEXT_MAX
                    + " characters; shorten it in the review first");
        }
        return value;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
