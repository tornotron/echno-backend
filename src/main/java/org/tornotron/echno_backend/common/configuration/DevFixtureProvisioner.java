package org.tornotron.echno_backend.common.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationOnboardingSeeder;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;

/**
 * Gives the local-development fixture account the application half of its identity.
 *
 * <p>An identity in Echno is two halves that are stored in two different places, and a login only
 * works when both are present. Spring authorities come from the Keycloak group claims, so a role
 * check such as {@code @orgSecurity.hasAnyOrgRoleForCurrentTenant(...)} passes only when the account
 * sits in an {@code /org-{id}/{role}} subgroup. The organization picker resolves membership through
 * the database instead: {@code GET /organization/web} runs
 * {@code JOIN o.employees e JOIN e.user u} and lists nothing without an {@code Employee} row.
 * {@link KeycloakInitializer} can create only the Keycloak half, so a fixture it provisions alone
 * has no organization to select and no role to pass a check with.
 *
 * <p>This provisioner supplies the missing half through the application's own onboarding path
 * rather than a second one: {@link EmployeeService#joinOrganization} writes the employee row and the
 * parent-group membership together, and {@link EmployeeService#assignOrgRole} adds the role
 * subgroup. That is exactly the sequence {@code OrganizationService.addOrganization} runs for a
 * self-service signup, so the fixture ends up shaped like a real account instead of a special case.
 *
 * <p>The fixture gets its <b>own</b> organization, created here and used by nothing else. It never
 * joins a seeded QA organization, for two reasons. A seeded organization exists only where the
 * deployment's seed playbook has been run, so borrowing one produces a fixture that works on
 * staging and nowhere else, which is the state this replaces. And every developer would then share
 * one identity inside QA data, editing rows that QA is asserting against. A dedicated organization
 * costs one extra creation step and keeps the fixture's writes where nothing else is looking.
 *
 * <p>Because that organization is private and disposable, the fixture is made its system admin: the
 * level the product itself grants whoever creates an organization, and the level a developer needs
 * to reach the screens behind the first one. The authority stops at the organization boundary, so it
 * confers nothing over seeded or real data. The fixture holds no realm admin role.
 *
 * <p>Every step is idempotent, so a restart repairs a half-finished fixture instead of duplicating
 * it. Nothing here runs unless {@code keycloak.dev-client-enabled} is true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevFixtureProvisioner {

    /** Name of the organization created for the fixture. Looked up by name to stay idempotent. */
    static final String DEV_ORG_NAME = "Local Development";
    static final String DEV_ORG_EMAIL = "local-dev-org@echno.local";
    static final String DEV_ORG_ADDRESS = "Local development fixture, no physical address";
    static final String DEV_ORG_PHONE = "0000000000";

    /** Placeholder profile values. The employee row copies these, and they are NOT NULL columns. */
    static final String DEV_USER_GENDER = "unspecified";
    static final String DEV_USER_PHONE = "0000000001";
    private static final LocalDateTime DEV_USER_DATE_OF_BIRTH = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** The role the fixture holds, and only inside the organization created below. */
    static final OrgRole DEV_ORG_ROLE = OrgRole.SYSTEM_ADMIN;

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeService employeeService;
    private final KeycloakGroupService keycloakGroupService;
    private final OrganizationOnboardingSeeder onboardingSeeder;

    /**
     * Brings the fixture's application identity up to date: a user row bound to the Keycloak
     * account, a development organization, an employee row joining the two, and the org role.
     *
     * @param keycloakUserId the fixture account's Keycloak id, which is the JWT subject the
     *                       application resolves a user by.
     * @param displayName    the name to store on the user row and copy onto the employee row.
     * @param email          the address the organization picker matches on.
     */
    public void provision(String keycloakUserId, String displayName, String email) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            log.error("No Keycloak id for the dev fixture account; skipping its application identity. "
                    + "The account would be able to log in but would see no organization.");
            return;
        }
        User user = ensureUser(keycloakUserId, displayName, email);
        Organization organization = ensureOrganization(user);
        if (organization == null) {
            return;
        }
        Long employeeId = ensureEmployee(user, organization);
        // Reconciled on every pass rather than only at creation, so a run whose seeding failed after
        // the organization row was written repairs itself on the next startup instead of leaving a
        // permanently half-provisioned organization. Each seeder skips an organization that already
        // has its data, so repeating this costs three lookups.
        onboardingSeeder.seedFinanceDefaults(organization.getId());
        assignOrgRole(organization.getId(), employeeId);
        log.warn("Dev fixture identity ready: user {} is a {} of organization {} ('{}')",
                user.getId(), DEV_ORG_ROLE.getGroupName(), organization.getId(), DEV_ORG_NAME);
    }

    /**
     * Finds or creates the user row the Keycloak account resolves to. The profile fields are filled
     * in because {@code joinOrganization} copies name, gender, phone, email and date of birth onto
     * the employee row, where all five are NOT NULL.
     */
    private User ensureUser(String keycloakUserId, String displayName, String email) {
        return userRepository.findUserByKeycloakId(keycloakUserId)
                .orElseGet(() -> {
                    User user = new User();
                    user.setKeycloakId(keycloakUserId);
                    user.setName(displayName);
                    user.setEmail(email);
                    user.setGender(DEV_USER_GENDER);
                    user.setPhone(DEV_USER_PHONE);
                    user.setDateOfBirth(DEV_USER_DATE_OF_BIRTH);
                    user.setRole(UserRole.EMPLOYEE);
                    User saved = userRepository.save(user);
                    log.info("Created the dev fixture user row (id {})", saved.getId());
                    return saved;
                });
    }

    /**
     * Finds or creates the fixture's own organization, or returns null when the name is taken by an
     * organization that is not the fixture's.
     *
     * <p>The name alone cannot decide that. Where self-service registration is on, any authenticated
     * user can create an organization and call it whatever they like, including this name, and
     * adopting it would hand the fixture system-admin over somebody else's tenant: the exact failure
     * this class exists to stop, reached from the other direction. So an existing organization is
     * adopted only when it also carries the fixture's own contact address and was created by the
     * fixture's user row, neither of which another tenant can set. Anything else is refused, and the
     * fixture is left unprovisioned rather than pointed at a stranger's data.
     */
    private Organization ensureOrganization(User creator) {
        Organization existing = organizationRepository.findOrganizationByOrganizationName(DEV_ORG_NAME)
                .orElse(null);
        if (existing != null) {
            if (!isFixtureOrganization(existing, creator)) {
                log.error("An organization named '{}' (id {}) already exists and is not the development "
                                + "fixture's own, so it belongs to a real tenant. Leaving it alone and "
                                + "skipping the fixture.",
                        DEV_ORG_NAME, existing.getId());
                return null;
            }
            return existing;
        }

        Organization organization = new Organization();
        organization.setOrganizationName(DEV_ORG_NAME);
        organization.setOrganizationEmail(DEV_ORG_EMAIL);
        organization.setOrganizationAddress(DEV_ORG_ADDRESS);
        organization.setOrganizationPhone(DEV_ORG_PHONE);
        organization.setCreatedAt(LocalDateTime.now());
        organization.setCreatorId(creator.getId() == null ? null : creator.getId().intValue());
        organization.setIsActive(true);
        Organization saved = organizationRepository.save(organization);
        log.info("Created the dev fixture organization '{}' (id {})", DEV_ORG_NAME, saved.getId());
        return saved;
    }

    /** True only for an organization this class created for this fixture user. */
    private static boolean isFixtureOrganization(Organization organization, User creator) {
        return DEV_ORG_EMAIL.equalsIgnoreCase(organization.getOrganizationEmail())
                && creator.getId() != null
                && organization.getCreatorId() != null
                && organization.getCreatorId().intValue() == creator.getId().intValue();
    }

    /**
     * Finds or creates the employee row. Creation goes through {@code joinOrganization}, which
     * writes the row and joins the parent {@code /org-{id}} group in one transaction, so the two
     * halves cannot drift apart. The Keycloak group has to exist before that join, so it is ensured
     * here rather than beside the organization row: a run that saved the row and then failed would
     * otherwise never get its group, and every later startup would skip straight past it.
     */
    private Long ensureEmployee(User user, Organization organization) {
        return employeeRepository.findByUserIdAndOrganizationId(user.getId(), organization.getId())
                .map(Employee::getId)
                .orElseGet(() -> {
                    ensureOrganizationGroup(organization);
                    EmployeeJoinOrgDto joinOrgDto = new EmployeeJoinOrgDto();
                    Long employeeId = employeeService
                            .joinOrganization(user.getId(), organization.getId(), joinOrgDto)
                            .getId();
                    log.info("Created the dev fixture employee row (id {}) in organization {}",
                            employeeId, organization.getId());
                    return employeeId;
                });
    }

    /**
     * Makes sure the organization's Keycloak group tree exists. {@code createOrganizationGroup} has
     * no create-if-absent form and rejects a name that is already taken, so a rejected create is how
     * we learn the group is there. A genuine Keycloak failure is not hidden by this: the join that
     * follows needs the group and reports it.
     */
    private void ensureOrganizationGroup(Organization organization) {
        try {
            keycloakGroupService.createOrganizationGroup(
                    organization.getId().toString(), organization.getOrganizationName());
            log.info("Created the Keycloak group for the dev fixture organization {}", organization.getId());
        } catch (Exception e) {
            log.info("Keycloak group for the dev fixture organization {} was not created now: {}",
                    organization.getId(), e.getMessage());
        }
    }

    /**
     * Adds the role subgroup membership the authority checks read. {@code assignOrgRole} resolves
     * the employee against {@code TenantContext}, so the tenant is set for the call and the previous
     * value restored afterwards, exactly as the onboarding seeder does.
     */
    private void assignOrgRole(Long organizationId, Long employeeId) {
        Long previous = TenantContext.getCurrentOrgId();
        TenantContext.setCurrentOrgId(organizationId);
        try {
            employeeService.assignOrgRole(employeeId, DEV_ORG_ROLE);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentOrgId(previous);
            }
        }
    }
}
