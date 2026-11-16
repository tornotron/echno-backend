package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrgRoleAuthority;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;
import org.tornotron.echno_backend.modules.assistant.dto.AskRequest;
import org.tornotron.echno_backend.modules.assistant.dto.AskResponse;
import org.tornotron.echno_backend.modules.assistant.dto.EvidenceDto;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;
import org.tornotron.echno_backend.modules.assistant.llm.FakeLlmClient;
import org.tornotron.echno_backend.modules.assistant.service.AssistantService;
import org.tornotron.echno_backend.modules.assistant.service.QueryLogService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.tornotron.echno_backend.user.User;

/**
 * The release gate. Two organizations on the real migrations, each with a project of the same
 * name, attendance and tasks of its own; the same question is asked as a member of each, through
 * the whole pipeline, the real services, the real tenant filter and the real guards. What must
 * hold: nothing of the other organization reaches the response or the audit row, a follow-up cannot
 * lean on the other organization's question, and one organization's log is invisible to the other.
 *
 * <p>The document provider is a stub here that always returns nothing: documents arrive with
 * ingestion, and until then the planner is shown a provider for them and it finds none.
 *
 * <p>The numbers are chosen to be told apart: organization A has twelve attendance records and
 * three tasks, organization B thirty-one and four, with titles that name their owner, so a response
 * that mixed them up would say so in its own words.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "keycloak-initializer.initialize-on-startup=false",
        "echno.cache.provider=caffeine",
        "echno.modules.assistant.enabled=true"
})
class AssistantIsolationIT extends AbstractIntegrationTest {

    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String SHARED_NAME = "Riverside Tower";
    private static final String ATTENDANCE_QUESTION = "How many workers were on site at Riverside Tower this week?";
    private static final String TASK_QUESTION = "Which tasks are overdue at Riverside Tower?";
    private static final int A_RECORDS = 12;
    private static final int B_RECORDS = 31;

    /** Stands in for the document provider, which arrives with ingestion: always finds nothing. */
    @TestConfiguration
    static class DocumentStub {
        @Bean
        AssistantProvider documentStub() {
            ProviderDescriptor descriptor = new ProviderDescriptor("document", "Passages of uploaded documents.",
                    List.of(new FieldSpec("passage", "text", "A passage of an uploaded document.")),
                    Set.of(Subject.DOCUMENT), false, false, new CostProfile(1, 0));
            return new AssistantProvider() {
                @Override
                public ProviderDescriptor describe() {
                    return descriptor;
                }

                @Override
                public ProviderResult retrieve(Question question, Scope scope) {
                    return new ProviderResult.Empty("documents of project " + scope.projectId());
                }
            };
        }
    }

    @Autowired
    private AssistantService service;

    @Autowired
    private QueryLogService queryLog;

    @Autowired
    private FakeLlmClient fake;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final String runTag = UUID.randomUUID().toString();
    private Long orgA;
    private Long orgB;
    private Long projectA;
    private Long projectB;
    private Long secretProjectB;
    private String managerA;
    private String managerB;
    private String plainMemberA;
    private final Set<String> taskIdsA = new HashSet<>();
    private final Set<String> taskIdsB = new HashSet<>();

    @BeforeEach
    void seed() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        fake.reset();
        LocalDate today = LocalDate.now(SITE_ZONE);

        inCommittedTx(() -> {
            Organization a = persistOrganization("Assistant Isolation Org A");
            Organization b = persistOrganization("Assistant Isolation Org B");
            orgA = a.getId();
            orgB = b.getId();
            projectA = persistProject(a, SHARED_NAME).getId();
            projectB = persistProject(b, SHARED_NAME).getId();
            secretProjectB = persistProject(b, "Org B Secret Tower").getId();

            Employee creatorA = persistEmployee(a, "Manager A");
            Employee creatorB = persistEmployee(b, "Manager B");
            managerA = creatorA.getUser().getKeycloakId();
            managerB = creatorB.getUser().getKeycloakId();
            plainMemberA = persistEmployee(a, "Mason A").getUser().getKeycloakId();

            for (int i = 0; i < A_RECORDS; i++) {
                persistAttendance(a, projectA, today, i);
            }
            for (int i = 0; i < B_RECORDS; i++) {
                persistAttendance(b, projectB, today, i);
            }
            taskIdsA.add("task:" + persistTask(a, projectA, creatorA, "A task: pour the raft", TaskStatus.onGoing, 40.0).getId());
            taskIdsA.add("task:" + persistTask(a, projectA, creatorA, "A task: fix the formwork", TaskStatus.upcoming, 0.0).getId());
            taskIdsA.add("task:" + persistTask(a, projectA, creatorA, "A task: cure the slab", TaskStatus.completed, 100.0).getId());
            taskIdsB.add("task:" + persistTask(b, projectB, creatorB, "B task: excavate", TaskStatus.onGoing, 55.0).getId());
            taskIdsB.add("task:" + persistTask(b, projectB, creatorB, "B task: shuttering", TaskStatus.onGoing, 10.0).getId());
            taskIdsB.add("task:" + persistTask(b, projectB, creatorB, "B task: rebar", TaskStatus.upcoming, 0.0).getId());
            taskIdsB.add("task:" + persistTask(b, projectB, creatorB, "B task: backfill", TaskStatus.onHold, 20.0).getId());
        });
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        fake.reset();
        if (orgA == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM assistant_query_log WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM task WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM attendance WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery("DELETE FROM users_table WHERE keycloak_id LIKE :tag")
                    .setParameter("tag", "assistant-it-" + runTag + "-%").executeUpdate();
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    // ---- the same question, asked as a member of each organization

    @Test
    void theSameAttendanceQuestionAnswersEachOrganizationFromItsOwnRecordsOnly() throws Exception {
        AskResponse forA = askAs(orgA, managerA, true, ATTENDANCE_QUESTION);
        AskResponse forB = askAs(orgB, managerB, true, ATTENDANCE_QUESTION);

        assertThat(forA.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(forB.outcome()).isEqualTo(AskOutcome.ANSWERED);

        assertThat(forA.evidence()).hasSize(1);
        assertThat(forB.evidence()).hasSize(1);
        assertThat(forA.evidence().get(0).values()).containsEntry("recordCount", A_RECORDS)
                .containsEntry("projectId", projectA);
        assertThat(forB.evidence().get(0).values()).containsEntry("recordCount", B_RECORDS)
                .containsEntry("projectId", projectB);
        assertThat(forA.evidence().get(0).id()).endsWith("project-" + projectA).doesNotContain("project-" + projectB);
        assertThat(forB.evidence().get(0).id()).endsWith("project-" + projectB).doesNotContain("project-" + projectA);
    }

    @Test
    void theSameTaskQuestionAnswersEachOrganizationFromItsOwnTasksOnly() throws Exception {
        AskResponse forA = askAs(orgA, managerA, true, TASK_QUESTION);
        AskResponse forB = askAs(orgB, managerB, true, TASK_QUESTION);

        assertThat(evidenceIds(forA)).isNotEmpty().allMatch(id -> id.startsWith("task-summary:") || taskIdsA.contains(id));
        assertThat(evidenceIds(forB)).isNotEmpty().allMatch(id -> id.startsWith("task-summary:") || taskIdsB.contains(id));
        assertThat(evidenceIds(forA)).doesNotContainAnyElementsOf(taskIdsB).doesNotContain("task-summary:project-" + projectB);
        assertThat(evidenceIds(forB)).doesNotContainAnyElementsOf(taskIdsA).doesNotContain("task-summary:project-" + projectA);

        assertThat(summaryOf(forA).values()).containsEntry("taskCount", 3);
        assertThat(summaryOf(forB).values()).containsEntry("taskCount", 4);

        String forAText = json.writeValueAsString(forA);
        String forBText = json.writeValueAsString(forB);
        assertThat(forAText).doesNotContain("B task");
        assertThat(forBText).doesNotContain("A task");
    }

    @Test
    void noTitleIdOrProjectOfTheOtherOrganizationAppearsInEitherResponse() throws Exception {
        AskResponse forA = askAs(orgA, managerA, true, TASK_QUESTION);
        AskResponse forB = askAs(orgB, managerB, true, TASK_QUESTION);

        String forAText = json.writeValueAsString(forA);
        String forBText = json.writeValueAsString(forB);
        taskIdsB.forEach(id -> assertThat(forAText).doesNotContain("\"" + id + "\""));
        taskIdsA.forEach(id -> assertThat(forBText).doesNotContain("\"" + id + "\""));
        assertThat(forAText).doesNotContain("Org B Secret Tower").doesNotContain("project-" + projectB);
        assertThat(forBText).doesNotContain("project-" + projectA);
    }

    @Test
    void aProjectOnlyTheOtherOrganizationHasIsNotFoundNeverResolved() {
        AskResponse response = askAs(orgA, managerA, true, "How many workers were on site at Org B Secret Tower?");

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(response.clarification()).singleElement().asString().contains("could not find");
        assertThat(response.evidence()).isEmpty();
    }

    // ---- the audit rows

    @Test
    void eachOrganizationsAuditLogHoldsOnlyItsOwnQuestions() throws Exception {
        askAs(orgA, managerA, true, ATTENDANCE_QUESTION);
        askAs(orgA, managerA, true, TASK_QUESTION);
        askAs(orgB, managerB, true, ATTENDANCE_QUESTION);

        List<QueryLogDto> seenByA = asTenant(orgA, () -> queryLog.all(0, 50).getContent());
        List<QueryLogDto> seenByB = asTenant(orgB, () -> queryLog.all(0, 50).getContent());

        assertThat(seenByA).hasSize(2).allSatisfy(row -> assertThat(row.projectId()).isEqualTo(projectA));
        assertThat(seenByB).hasSize(1).allSatisfy(row -> assertThat(row.projectId()).isEqualTo(projectB));
        // Nothing of B in A's rows or the reverse, in the rows' own words.
        String textA = json.writeValueAsString(seenByA);
        String textB = json.writeValueAsString(seenByB);
        assertThat(textA).doesNotContain("project-" + projectB).doesNotContain("B task");
        assertThat(textB).doesNotContain("project-" + projectA).doesNotContain("A task");
    }

    @Test
    void theAuditRowRecordsTheEvidenceAndNoNumberFromAnotherOrganization() throws Exception {
        askAs(orgA, managerA, true, ATTENDANCE_QUESTION);

        QueryLogDto row = asTenant(orgA, () -> queryLog.all(0, 10).getContent()).get(0);

        assertThat(row.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(row.providersSelected()).containsExactly("attendance");
        assertThat(row.evidenceIds()).hasSize(1).allMatch(id -> id.endsWith("project-" + projectA));
        String answerText = json.writeValueAsString(row.answer());
        assertThat(answerText).contains("recordCount\\\":" + A_RECORDS).doesNotContain("recordCount\\\":" + B_RECORDS);
    }

    @Test
    void aMembersHistoryHoldsTheirOwnQuestionsAndNotAnotherUsers() {
        askAs(orgA, managerA, true, ATTENDANCE_QUESTION);
        askAs(orgA, plainMemberA, false, TASK_QUESTION);

        List<QueryLogDto> managers = asUser(orgA, managerA, true, () -> service.history(0, 20).getContent());
        List<QueryLogDto> members = asUser(orgA, plainMemberA, false, () -> service.history(0, 20).getContent());

        assertThat(managers).hasSize(1).allSatisfy(r -> assertThat(r.question()).isEqualTo(ATTENDANCE_QUESTION));
        assertThat(members).hasSize(1).allSatisfy(r -> assertThat(r.question()).isEqualTo(TASK_QUESTION));
    }

    @Test
    void aFollowUpCannotLeanOnAnotherOrganizationsQuestion() {
        AskResponse asked = askAs(orgA, managerA, true, ATTENDANCE_QUESTION);

        // B's manager quotes A's query id and names no project of their own.
        AskResponse followUp = askAs(orgB, managerB, true, "And which tasks are overdue?", asked.queryId());

        assertThat(followUp.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(followUp.evidence()).isEmpty();
        assertThat(followUp.assumptions()).noneMatch(a -> a.contains("previous question"));
    }

    @Test
    void aFollowUpCannotLeanOnAnotherUsersQuestionInTheSameOrganization() {
        AskResponse asked = askAs(orgA, managerA, true, ATTENDANCE_QUESTION);

        AskResponse followUp = askAs(orgA, plainMemberA, false, "And which tasks are overdue?", asked.queryId());

        assertThat(followUp.outcome()).isEqualTo(AskOutcome.CLARIFY);
    }

    @Test
    void aFollowUpByTheSameUserInTheSameOrganizationDoesLeanOnIt() {
        AskResponse asked = askAs(orgA, managerA, true, ATTENDANCE_QUESTION);

        AskResponse followUp = askAs(orgA, managerA, true, "And which tasks are overdue?", asked.queryId());

        assertThat(followUp.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(evidenceIds(followUp)).anyMatch(taskIdsA::contains);
    }

    // ---- the guards, as the pipeline meets them

    @Test
    void aMemberWhoMayNotReadAttendanceGetsNoAttendanceEvidenceButStillGetsTasks() {
        AskResponse attendance = askAs(orgA, plainMemberA, false, ATTENDANCE_QUESTION);
        AskResponse tasks = askAs(orgA, plainMemberA, false, TASK_QUESTION);

        assertThat(attendance.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(attendance.evidence()).isEmpty();
        assertThat(attendance.lookedFor()).singleElement().asString().contains("attendance").contains("do not have access");
        assertThat(tasks.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(evidenceIds(tasks)).anyMatch(taskIdsA::contains);
    }

    @Test
    void aMemberOfOneOrganizationCannotAskAsAnother() {
        // A's manager's identity, but pinned to B's tenant: they hold no membership or role there.
        AskResponse response = askAs(orgB, managerA, true, ATTENDANCE_QUESTION, null, orgA);

        assertThat(response.evidence()).isEmpty();
        assertThat(response.outcome()).isIn(AskOutcome.CLARIFY, AskOutcome.DECLINED);
    }

    // ---- the document provider, stubbed

    @Test
    void aDocumentQuestionMeetsTheStubAndIsDeclinedNamingWhatWasLookedFor() {
        fake.whenInterpreting("What do the documents say about the raft at Riverside Tower?",
                "{\"subjects\":[\"DOCUMENT\"],\"projectRef\":\"Riverside Tower\",\"ambiguous\":[]}");

        AskResponse forA = askAs(orgA, managerA, true, "What do the documents say about the raft at Riverside Tower?");

        assertThat(forA.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(forA.lookedFor()).singleElement().asString().isEqualTo("document: documents of project " + projectA);
        assertThat(forA.evidence()).isEmpty();
    }

    // ---- helpers

    private AskResponse askAs(Long org, String keycloakId, boolean manager, String question) {
        return askAs(org, keycloakId, manager, question, null, org);
    }

    private AskResponse askAs(Long org, String keycloakId, boolean manager, String question, UUID previous) {
        return askAs(org, keycloakId, manager, question, previous, org);
    }

    /** Asks as {@code keycloakId}, pinned to {@code org}; the authorities are those held in {@code authoritiesOrg}. */
    private AskResponse askAs(Long org, String keycloakId, boolean manager, String question, UUID previous, Long authoritiesOrg) {
        return asUser(org, keycloakId, manager, authoritiesOrg, () -> service.ask(new AskRequest(question, previous)));
    }

    private <T> T asUser(Long org, String keycloakId, boolean manager, Supplier<T> work) {
        return asUser(org, keycloakId, manager, org, work);
    }

    private <T> T asUser(Long org, String keycloakId, boolean manager, Long authoritiesOrg, Supplier<T> work) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ORG_MEMBER_" + authoritiesOrg));
        if (manager) {
            authorities.add(new SimpleGrantedAuthority(OrgRoleAuthority.of(authoritiesOrg, "project-manager")));
        }
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(keycloakId, null, authorities));
        TenantContext.setCurrentOrgId(org);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private <T> T asTenant(Long org, Supplier<T> work) {
        TenantContext.setCurrentOrgId(org);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static List<String> evidenceIds(AskResponse response) {
        return response.evidence().stream().map(EvidenceDto::id).toList();
    }

    private static EvidenceDto summaryOf(AskResponse response) {
        return response.evidence().stream().filter(e -> e.id().startsWith("task-summary:")).findFirst().orElseThrow();
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

    private Employee persistEmployee(Organization org, String name) {
        User user = new User();
        user.setKeycloakId("assistant-it-" + runTag + "-" + UUID.randomUUID());
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("AIT-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "." + UUID.randomUUID() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setStatus(EmployeeStatus.active);
        employee.setOrgRoles(new HashSet<>());
        entityManager.persist(employee);
        entityManager.flush();
        return employee;
    }

    private void persistAttendance(Organization org, Long projectId, LocalDate date, int index) {
        Attendance attendance = Attendance.builder()
                .employeeId(900_000L + index)
                .employeeName("Worker " + index)
                .attendanceDate(date)
                .projectId(projectId)
                .projectName(SHARED_NAME)
                .status(AttendanceStatus.PRESENT)
                .approvalStatus(ApprovalStatus.PENDING)
                .requiresGeofenceApproval(false)
                .organization(org)
                .build();
        entityManager.persist(attendance);
        entityManager.flush();
    }

    private Task persistTask(Organization org, Long projectId, Employee creator, String title, TaskStatus status, double progress) {
        Task task = new Task();
        task.setTitle(title);
        task.setStatus(status);
        task.setProgress(progress);
        task.setCreator(creator);
        task.setProject(entityManager.getReference(Project.class, projectId));
        task.setOrganization(org);
        task.setEndDate(LocalDateTime.now(SITE_ZONE).plusDays(10));
        entityManager.persist(task);
        entityManager.flush();
        return task;
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql).setParameter("a", orgA).setParameter("b", orgB).executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }
}
