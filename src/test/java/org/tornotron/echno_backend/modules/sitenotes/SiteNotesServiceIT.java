package org.tornotron.echno_backend.modules.sitenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
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
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteAddedEvent;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;
import org.tornotron.echno_backend.modules.sitenotes.dto.UpdateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.mapper.SiteNotesMapperImpl;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's tenant-isolation proof on the real migration, and every rule of the service: a
 * note added in one organization reads as absent from another, by id and by list; the project
 * and the author must belong to the caller's organization; the date may be at most a day ahead.
 */
@DataJpaTest
@RecordApplicationEvents
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SiteNotesService.class, SiteNotesMapperImpl.class, UserContextService.class, TenantEntityHelper.class})
class SiteNotesServiceIT extends AbstractIntegrationTest {

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
            authorAId = persistEmployee(orgA, "Supervisor A", EmployeeStatus.active).getId();
            authorBId = persistEmployee(orgB, "Supervisor B", EmployeeStatus.active).getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
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
        SiteNoteDto created = service.create(add(projectAId, authorAId, LocalDate.now()));

        assertThat(created.id()).isNotNull();
        assertThat(created.note()).isEqualTo("Pour started at seven");
        assertThat(service.get(created.id()).authorEmployeeId()).isEqualTo(authorAId);
        assertThat(service.list(projectAId, null, null, 0, 10).getContent())
                .extracting(SiteNoteDto::id).containsExactly(created.id());
    }

    @Test
    void anotherTenantsNoteReadsAsAbsent() {
        UUID foreign = asTenant(orgBId, () -> service.create(add(projectBId, authorBId, LocalDate.now())).id());

        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(foreign, new UpdateSiteNoteRequest(LocalDate.now(), "Changed")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.list(null, null, null, 0, 10).getContent()).isEmpty();
    }

    @Test
    void aNoteIsDatedNoMoreThanADayAhead() {
        assertThat(service.create(add(projectAId, authorAId, LocalDate.now().plusDays(1))).id()).isNotNull();

        assertThatThrownBy(() -> service.create(add(projectAId, authorAId, LocalDate.now().plusDays(2))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theProjectAndTheAuthorMustBelongToTheCallersOrganization() {
        Long resigned = inCommittedTxReturning(() ->
                persistEmployee(entityManager.getReference(Organization.class, orgAId), "Left Already",
                        EmployeeStatus.resigned).getId());

        assertThatThrownBy(() -> service.create(add(projectAId, authorBId, LocalDate.now())))
                .as("an author from another organization")
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(add(projectAId, resigned, LocalDate.now())))
                .as("an author who has resigned")
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(add(projectBId, authorAId, LocalDate.now())))
                .as("another organization's project")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addingANotePublishesTheEvent() {
        SiteNoteDto created = service.create(add(projectAId, authorAId, LocalDate.now()));

        assertThat(applicationEvents.stream(SiteNoteAddedEvent.class))
                .singleElement()
                .isEqualTo(new SiteNoteAddedEvent(orgAId, created.id(), projectAId, LocalDate.now(), authorAId));
    }

    @Test
    void aNoteIsChangedAndListedByDateRange() {
        LocalDate today = LocalDate.now();
        UUID id = service.create(add(projectAId, authorAId, today.minusDays(3))).id();

        SiteNoteDto changed = service.update(id, new UpdateSiteNoteRequest(today, "Pour finished"));

        assertThat(changed.note()).isEqualTo("Pour finished");
        assertThat(changed.noteDate()).isEqualTo(today);
        assertThat(changed.authorEmployeeId()).isEqualTo(authorAId);
        assertThat(service.list(projectAId, today.minusDays(1), today, 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(service.list(projectAId, today.minusDays(9), today.minusDays(2), 0, 10).getTotalElements()).isZero();
    }

    private static CreateSiteNoteRequest add(Long projectId, Long authorId, LocalDate date) {
        return new CreateSiteNoteRequest(projectId, date, authorId, "Pour started at seven");
    }

    private <T> T asTenant(Long orgId, Supplier<T> work) {
        Long previous = TenantContext.getCurrentOrgId();
        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgId);
        enableOrgFilter(orgId);
        try {
            return work.get();
        } finally {
            disableOrgFilter();
            TenantContext.setCurrentOrgId(previous);
            enableOrgFilter(previous);
        }
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

    private <T> T inCommittedTxReturning(Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> work.get());
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }
}
