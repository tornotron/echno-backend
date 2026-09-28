package org.tornotron.echno_backend.modules.sitenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteAddedEvent;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;
import org.tornotron.echno_backend.modules.sitenotes.dto.UpdateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.mapper.SiteNotesMapperImpl;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;
import org.tornotron.echno_backend.modules.sitenotes.time.SiteNotesClock;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's tenant-isolation proof on the real migration, and every rule of the service: a
 * note added in one organization reads as absent from another, by id, by list and by photo; the
 * project must belong to the caller's organization and the author is always the caller, never a
 * request field; a note is dated within the module's window; adding one publishes the event.
 *
 * <p>The clock is fixed rather than the system clock, both because a run that happens to cross a
 * day boundary must not flip a date-boundary assertion, and because the service and this test
 * would otherwise each call {@code now()} independently and could disagree near midnight IST,
 * the exact bug the date rule exists to avoid.
 */
@DataJpaTest
@RecordApplicationEvents
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SiteNotesService.class, SiteNotesMapperImpl.class, UserContextService.class, TenantEntityHelper.class,
        CurrentEmployeeService.class, OrganizationSecurityService.class,
        SiteNotesServiceIT.FixedClockConfig.class})
class SiteNotesServiceIT extends AbstractIntegrationTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);

    @Autowired
    private SiteNotesService service;

    // The photo path is the platform's; this IT proves the module's own rows and rules.
    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private AttachmentMapper attachmentMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ApplicationEvents applicationEvents;

    private final String runTag = UUID.randomUUID().toString();
    private Long orgAId;
    private Long orgBId;
    private Long projectAId;
    private Long projectBId;
    private Long authorAId;
    private Long authorBId;
    private Long resignedAId;
    private String authorAKeycloakId;
    private String authorBKeycloakId;
    private String resignedAKeycloakId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Site Notes Org A");
            Organization orgB = persistOrganization("Site Notes Org B");
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectAId = persistProject(orgA, "Tower A").getId();
            projectBId = persistProject(orgB, "Tower B").getId();
            Employee authorA = persistEmployee(orgA, "Supervisor A", EmployeeStatus.active);
            Employee authorB = persistEmployee(orgB, "Supervisor B", EmployeeStatus.active);
            Employee resignedA = persistEmployee(orgA, "Left Already", EmployeeStatus.resigned);
            authorAId = authorA.getId();
            authorBId = authorB.getId();
            resignedAId = resignedA.getId();
            authorAKeycloakId = authorA.getUser().getKeycloakId();
            authorBKeycloakId = authorB.getUser().getKeycloakId();
            resignedAKeycloakId = resignedA.getUser().getKeycloakId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
        setCaller(authorAKeycloakId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM site_note WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery("DELETE FROM users_table WHERE keycloak_id LIKE :tag")
                    .setParameter("tag", "site-notes-it-" + runTag + "-%")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void aNoteIsAddedInTheCallersTenantAndReadBack() {
        SiteNoteDto created = service.create(add(projectAId));

        assertThat(created.id()).isNotNull();
        assertThat(created.note()).isEqualTo("Pour started at seven");
        assertThat(created.authorEmployeeId()).isEqualTo(authorAId);
        assertThat(service.get(created.id()).authorEmployeeId()).isEqualTo(authorAId);
        assertThat(service.list(projectAId, null, null, 0, 10).getContent())
                .extracting(SiteNoteDto::id).containsExactly(created.id());
    }

    @Test
    void anotherTenantsNoteReadsAsAbsentByIdByListAndByPhotos() {
        UUID own = service.create(add(projectAId)).id();
        UUID foreign = asTenant(orgBId, authorBKeycloakId, () -> service.create(add(projectBId)).id());

        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(foreign, new UpdateSiteNoteRequest(TODAY, "Changed")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listPhotos(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.presignPhotos(foreign, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        // Not empty either way, as it would be if the foreign id simply matched nothing: org A
        // has a note of its own, and that is the one that must come back.
        assertThat(service.list(null, null, null, 0, 10).getContent())
                .extracting(SiteNoteDto::id).containsExactly(own);
    }

    @Test
    void aNoteIsDatedNoMoreThanADayAheadOrTooFarBehind() {
        assertThat(service.create(add(projectAId, TODAY.plusDays(1))).id()).isNotNull();
        assertThat(service.create(add(projectAId, TODAY.minusDays(60))).id())
                .isNotNull();

        assertThatThrownBy(() -> service.create(add(projectAId, TODAY.plusDays(2))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(add(projectAId, TODAY.minusDays(61))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void updateRefusesADateTooFarAhead() {
        UUID id = service.create(add(projectAId)).id();

        assertThatThrownBy(() -> service.update(id, new UpdateSiteNoteRequest(TODAY.plusDays(2), "Changed")))
                .isInstanceOf(InvalidRequestException.class);
        // The original survives a rejected update.
        assertThat(service.get(id).noteDate()).isEqualTo(TODAY);
    }

    @Test
    void aNoteBelongsToTheCallersOrganizationsProject() {
        assertThatThrownBy(() -> service.create(add(projectBId)))
                .as("another organization's project")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theCallerMustBeAnActiveEmployeeOfTheOrganization() {
        setCaller(resignedAKeycloakId);

        assertThatThrownBy(() -> service.create(add(projectAId)))
                .as("a resigned caller")
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void addingANoteRequiresAnEmployeeRecord() {
        // A keycloak id with no matching user row at all: the session resolves to nobody, which
        // is the shape a bootstrap administrator or a caller outside this organization has.
        setCaller("site-notes-it-" + runTag + "-no-such-user");

        assertThatThrownBy(() -> service.create(add(projectAId)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addingANotePublishesTheEvent() {
        SiteNoteDto created = service.create(add(projectAId));

        assertThat(applicationEvents.stream(SiteNoteAddedEvent.class))
                .singleElement()
                .isEqualTo(new SiteNoteAddedEvent(orgAId, created.id(), projectAId, TODAY, authorAId));
    }

    @Test
    void aNoteIsChangedAndListedByDateRange() {
        UUID id = service.create(add(projectAId, TODAY.minusDays(3))).id();

        SiteNoteDto changed = service.update(id, new UpdateSiteNoteRequest(TODAY, "Pour finished"));

        assertThat(changed.note()).isEqualTo("Pour finished");
        assertThat(changed.noteDate()).isEqualTo(TODAY);
        assertThat(changed.authorEmployeeId()).isEqualTo(authorAId);
        assertThat(service.list(projectAId, TODAY.minusDays(1), TODAY, 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(service.list(projectAId, TODAY.minusDays(9), TODAY.minusDays(2), 0, 10).getTotalElements()).isZero();
    }

    private static CreateSiteNoteRequest add(Long projectId) {
        return add(projectId, TODAY);
    }

    private static CreateSiteNoteRequest add(Long projectId, LocalDate date) {
        return new CreateSiteNoteRequest(projectId, date, "Pour started at seven");
    }

    /** Switches tenant and caller together, then restores this test's default (org A, author A). */
    private <T> T asTenant(Long orgId, String keycloakId, Supplier<T> work) {
        Long previousOrgId = TenantContext.getCurrentOrgId();
        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgId);
        enableOrgFilter(orgId);
        setCaller(keycloakId);
        try {
            return work.get();
        } finally {
            disableOrgFilter();
            TenantContext.setCurrentOrgId(previousOrgId);
            enableOrgFilter(previousOrgId);
            setCaller(authorAKeycloakId);
        }
    }

    private static void setCaller(String keycloakId) {
        // The 2-arg constructor leaves the token unauthenticated (no authorities supplied); the
        // 3-arg one marks it authenticated, which is what UserContextService checks for.
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(keycloakId, null, List.of()));
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        return project;
    }

    private Employee persistEmployee(Organization org, String name, EmployeeStatus status) {
        User user = new User();
        user.setKeycloakId("site-notes-it-" + runTag + "-" + UUID.randomUUID());
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("SN-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setStatus(status);
        employee.setOrgRoles(new HashSet<>());
        entityManager.persist(employee);
        entityManager.flush();
        return employee;
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean(defaultCandidate = false)
        @SiteNotesClock
        Clock siteNotesTestClock() {
            return FIXED_CLOCK;
        }
    }
}
