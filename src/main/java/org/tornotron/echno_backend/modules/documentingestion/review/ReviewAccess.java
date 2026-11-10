package org.tornotron.echno_backend.modules.documentingestion.review;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;

/**
 * Which candidates the caller may review, on the access matrix (design section 6.6).
 *
 * <p>The decisions endpoint is open to anyone holding one of the three review actions, and one
 * request may decide candidates of several kinds, so the kind is asked here, per candidate:
 * {@code review} covers every kind, {@code review-quality} only quality requirements and
 * {@code review-drawings} only drawings. The starting roles give {@code review} to the system admin
 * and the project manager, {@code review-quality} to the QA engineer and {@code review-drawings} to
 * the site engineer, which is who reviewed each kind before the matrix.
 */
@Component
public class ReviewAccess {

    static final Set<CandidateKind> QUALITY_KINDS = Set.of(CandidateKind.QUALITY_REQUIREMENT);
    static final Set<CandidateKind> DRAWING_KINDS = Set.of(CandidateKind.DRAWING);

    private static final AccessPermission REVIEW = AccessPermission.of(DocumentIngestionModule.PERMISSION_REVIEW);
    private static final AccessPermission REVIEW_QUALITY =
            AccessPermission.of(DocumentIngestionModule.PERMISSION_REVIEW_QUALITY);
    private static final AccessPermission REVIEW_DRAWINGS =
            AccessPermission.of(DocumentIngestionModule.PERMISSION_REVIEW_DRAWINGS);

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public ReviewAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    /** Whether the caller may accept, edit or reject a candidate of this kind. */
    public boolean mayReview(CandidateKind kind) {
        Optional<AccessSubject> subject = subject();
        if (subject.isEmpty()) {
            return false;
        }
        if (access.check(subject.get(), REVIEW)) {
            return true;
        }
        if (QUALITY_KINDS.contains(kind) && access.check(subject.get(), REVIEW_QUALITY)) {
            return true;
        }
        return DRAWING_KINDS.contains(kind) && access.check(subject.get(), REVIEW_DRAWINGS);
    }

    private Optional<AccessSubject> subject() {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (organizationId == null) {
            return Optional.empty();
        }
        return currentEmployee.currentEmployee().map(employee -> new AccessSubject(
                organizationId,
                employee.getId(),
                employee.getUser() == null ? null : employee.getUser().getId()));
    }
}
