package org.tornotron.echno_backend.leave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.leave.enums.AccrualMethod;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;

/**
 * The leave types a freshly created organization starts with: Casual, Sick, Earned, Maternity
 * and Paternity.
 *
 * <p>Idempotent per leave-type code, so a rerun for an organization that already holds a code
 * changes nothing and never overwrites what the organization has since configured. Nothing here
 * ran for organizations created before it existed; the one type back-filled for those is
 * Paternity, by changeset 123, keyed the same way. The defaults are a starting point an
 * administrator edits on the Leave Policies screen, which is why the quotas are the common
 * Indian ones and every rule is the policy's declared default unless the type itself demands
 * otherwise: maternity and paternity are credited in full once the employee qualifies, apply to
 * one gender, and need a supporting document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeavePolicyDefaultsSeeder {

    public static final String PATERNITY_CODE = "PL";
    public static final String PATERNITY_NAME = "Paternity Leave";

    private final LeavePolicyRepository policyRepository;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Seeds every default leave type the current organization does not already hold.
     *
     * @return How many policies were created.
     */
    @Transactional
    public int seedDefaults() {
        Long organizationId = TenantContext.getCurrentOrgId();
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        int created = 0;
        for (LeavePolicy template : defaults()) {
            if (policyRepository.existsByOrganizationIdAndLeaveTypeCode(organizationId, template.getLeaveTypeCode())) {
                continue;
            }
            template.setOrganization(organization);
            policyRepository.save(template);
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} default leave policies for organization {}", created, organizationId);
        }
        return created;
    }

    /** The five default policies, fresh instances each call so a save cannot alias a template. */
    static List<LeavePolicy> defaults() {
        return List.of(
                policy("CL", "Casual Leave", "Short-notice leave for personal matters.", 12.0, 0),
                policy("SL", "Sick Leave", "Leave for illness or medical care.", 12.0, 1),
                policy("EL", "Earned Leave", "Planned leave earned with service.", 15.0, 2),
                entitlement("ML", "Maternity Leave", "Leave for childbirth and recovery.", 182.0, "FEMALE",
                        "Medical certificate or hospital discharge summary", 3),
                entitlement(PATERNITY_CODE, PATERNITY_NAME, "Leave for a father around the birth of a child.",
                        7.0, "MALE", "Birth certificate or hospital discharge summary", 4));
    }

    private static LeavePolicy policy(String code, String name, String description, double quota, int order) {
        LeavePolicy policy = new LeavePolicy();
        policy.setLeaveTypeCode(code);
        policy.setLeaveTypeName(name);
        policy.setDescription(description);
        policy.setAnnualQuota(quota);
        policy.setDisplayOrder(order);
        policy.setIsActive(true);
        return policy;
    }

    private static LeavePolicy entitlement(String code, String name, String description, double quota,
                                           String gender, String documentNote, int order) {
        LeavePolicy policy = policy(code, name, description, quota, order);
        policy.setApplicableGenders(gender);
        policy.setAccrualMethod(AccrualMethod.IN_FULL_ON_QUALIFYING);
        policy.setRequiresAttachment(true);
        policy.setSupportingDocumentNote(documentNote);
        policy.setAllowHalfDay(false);
        return policy;
    }
}
