package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the eligibility query on {@link LeavePolicyRepository}, run against
 * a real CockroachDB (see {@link AbstractIntegrationTest}).
 *
 * <p>Employee records hold a gender in title case ("Female"), because that is what the
 * registration form writes, while a leave policy is configured with a code-style value
 * ("FEMALE"). An exact string comparison therefore matched neither way round, so a policy
 * restricted to one gender applied to nobody and every policy had to be left as ALL,
 * which is why every employee was offered maternity leave. These tests pin the comparison as
 * case-insensitive, and cover the service-months gate alongside it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LeavePolicyEligibilityIT extends AbstractIntegrationTest {

    @Autowired
    private LeavePolicyRepository policyRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findApplicablePolicies_matchesGenderRegardlessOfCase() {
        Organization org = persistOrganization("Eligibility Case Org");
        persistPolicy(org, "CL", "Casual Leave", "ALL", 0);
        persistPolicy(org, "ML", "Maternity Leave", "FEMALE", 0);
        em.flush();
        em.clear();

        List<LeavePolicy> forFemale = policyRepository.findApplicablePolicies(org.getId(), "Female", 24);

        assertThat(forFemale).extracting(LeavePolicy::getLeaveTypeCode)
                .containsExactlyInAnyOrder("CL", "ML");
    }

    @Test
    void findApplicablePolicies_excludesAPolicyForAnotherGender() {
        Organization org = persistOrganization("Eligibility Exclusion Org");
        persistPolicy(org, "CL", "Casual Leave", "ALL", 0);
        persistPolicy(org, "ML", "Maternity Leave", "FEMALE", 0);
        em.flush();
        em.clear();

        List<LeavePolicy> forMale = policyRepository.findApplicablePolicies(org.getId(), "Male", 24);

        assertThat(forMale).extracting(LeavePolicy::getLeaveTypeCode).containsExactly("CL");
    }

    @Test
    void findApplicablePolicies_withoutAGender_stillOffersThePoliciesOpenToEveryone() {
        Organization org = persistOrganization("Eligibility Null Gender Org");
        persistPolicy(org, "CL", "Casual Leave", "ALL", 0);
        persistPolicy(org, "ML", "Maternity Leave", "FEMALE", 0);
        em.flush();
        em.clear();

        List<LeavePolicy> forUnknown = policyRepository.findApplicablePolicies(org.getId(), null, 24);

        assertThat(forUnknown).extracting(LeavePolicy::getLeaveTypeCode).containsExactly("CL");
    }

    @Test
    void findApplicablePolicies_withholdsAPolicyUntilTheServiceMonthsAreMet() {
        Organization org = persistOrganization("Eligibility Service Org");
        persistPolicy(org, "CL", "Casual Leave", "ALL", 0);
        persistPolicy(org, "EL", "Earned Leave", "ALL", 12);
        em.flush();
        em.clear();

        assertThat(policyRepository.findApplicablePolicies(org.getId(), "Male", 6))
                .extracting(LeavePolicy::getLeaveTypeCode).containsExactly("CL");
        assertThat(policyRepository.findApplicablePolicies(org.getId(), "Male", 18))
                .extracting(LeavePolicy::getLeaveTypeCode).containsExactlyInAnyOrder("CL", "EL");
    }

    @Test
    void findApplicablePolicies_leavesAnInactivePolicyOut() {
        Organization org = persistOrganization("Eligibility Inactive Org");
        LeavePolicy retired = persistPolicy(org, "SL", "Sick Leave", "ALL", 0);
        retired.setIsActive(false);
        em.flush();
        em.clear();

        assertThat(policyRepository.findApplicablePolicies(org.getId(), "Male", 24)).isEmpty();
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private LeavePolicy persistPolicy(
            Organization org, String code, String name, String genders, int minServiceMonths) {
        LeavePolicy policy = new LeavePolicy();
        policy.setOrganization(org);
        policy.setLeaveTypeCode(code);
        policy.setLeaveTypeName(name);
        policy.setAnnualQuota(12.0);
        policy.setApplicableGenders(genders);
        policy.setMinServiceMonths(minServiceMonths);
        policy.setIsActive(true);
        em.persist(policy);
        return policy;
    }
}
