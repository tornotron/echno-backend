package org.tornotron.echno_backend.projection;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.category.mapper.CategoryMapper;
import org.tornotron.echno_backend.category.mapper.CategoryMapperImpl;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.dto.IndentSummaryDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.indent.mapper.IndentMapper;
import org.tornotron.echno_backend.indent.mapper.IndentMapperImpl;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemCountLookup;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapperImpl;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.issue.mapper.IssueMapperImpl;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.mapper.MaterialMapper;
import org.tornotron.echno_backend.material.mapper.MaterialMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapperImpl;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectProgressLookup;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSummaryDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.project.mapper.ProjectMapperImpl;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.tornotron.echno_backend.task.mapper.TaskMapper;
import org.tornotron.echno_backend.task.mapper.TaskMapperImpl;
import org.tornotron.echno_backend.user.User;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a list endpoint costs before and after the projection, in statements and in rows read.
 *
 * <p>Issue #522 asked whether the conversion layer was going to scale. Three response shapes carry
 * an entire object graph on every row of a list: {@code OrganizationDto} carries every project,
 * each project carries its team, its tasks and its attachments, and each task carries its own
 * issues, assignees and attachments; {@code ProjectDto} is the middle of that chain; and
 * {@code IndentDto} carries every line, each line carrying a whole material.
 *
 * <p>{@code MaterialMapperCostTest} pinned the equivalent numbers for the {@code @AfterMapping}
 * problem so that the fix showed up in a diff rather than only in a query log. This does the same
 * for the graph problem. Both directions are measured on the same fixture and in the same units,
 * against a real CockroachDB, with Hibernate's own statistics counting.
 *
 * <p>Two things are measured, because they answer different questions. Prepared statements say how
 * many round trips the request makes, and Hibernate's {@code default_batch_fetch_size} of 100 keeps
 * that number lower than an N+1 would suggest. Entity loads say how many rows were materialised,
 * and no batch setting reduces that: it is the number that follows the shape of the DTO, and it is
 * the one that grows without bound as a tenant's history does.
 *
 * <p>The strongest of the assertions below is not either absolute number. It is that the full view
 * costs more when the same page of rows has a deeper graph hanging off it, and the projection costs
 * exactly the same, so the projection's cost is a function of the page size alone.
 *
 * <p>What it measures, on the fixtures below:
 *
 * <pre>
 * a page of 5 projects, 6 tasks each, 3 issues per task
 *   ProjectDto             14 statements   128 entities   317 collections
 *   ProjectSummaryDto       3 statements     6 entities     0 collections
 *
 * the same 5 projects, shallow (2 tasks, 1 issue) against deep (8 tasks, 4 issues)
 *   ProjectDto             28 -> 208 entities,  77 -> 497 collections
 *   ProjectSummaryDto       6 ->   6 entities,   0 ->   0 collections
 *
 * one organization holding 4 projects, 4 tasks each, 2 issues per task
 *   OrganizationDto        16 statements    55 entities   145 collections
 *   OrganizationSimpleDto   1 statement       1 entity      0 collections
 *
 * a page of 5 indents, 10 lines each
 *   IndentDto               6 statements   109 entities     7 collections
 *   IndentSummaryDto        4 statements     8 entities     1 collection
 * </pre>
 *
 * <p>The indent figures understate the full view: {@code IndentDto} carries a material on every
 * line and a material carries its aggregate stock, which the service reads separately and which is
 * not counted here. And none of the fixtures is large. A tenant with a hundred tasks on a project
 * and a page of fifty projects multiplies the project row above by ten in the page and again in the
 * depth; the projection's six stay six.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ListProjectionCostIT extends AbstractIntegrationTest {

    private static final int PAGE = 5;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IndentItemRepository indentItemRepository;

    @Autowired
    private TestEntityManager em;

    @PersistenceContext
    private EntityManager entityManager;

    private ProjectMapper projectMapper;
    private OrganizationMapper organizationMapper;
    private IndentMapper indentMapper;

    private int nextSuffix;

    /**
     * Builds the mappers the way the container does. This is a repository slice, so the MapStruct
     * beans are not in the context; the generated implementations are what the application runs, so
     * they are what is measured.
     */
    private void wireMappers() {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn("https://store.example/signed");

        AttachmentMapper attachmentMapper = new AttachmentMapperImpl();
        ReflectionTestUtils.setField(attachmentMapper, "fileStorageService", fileStorageService);

        EmployeeMapper employeeMapper = new EmployeeMapperImpl();
        ReflectionTestUtils.setField(employeeMapper, "attachmentMapper", attachmentMapper);
        ReflectionTestUtils.setField(employeeMapper, "shiftTimingMapper", mock(ShiftTimingMapper.class));

        IssueMapper issueMapper = new IssueMapperImpl();
        ReflectionTestUtils.setField(issueMapper, "attachmentMapper", attachmentMapper);

        CategoryMapper categoryMapper = new CategoryMapperImpl();

        TaskMapper taskMapper = new TaskMapperImpl();
        ReflectionTestUtils.setField(taskMapper, "employeeMapper", employeeMapper);
        ReflectionTestUtils.setField(taskMapper, "categoryMapper", categoryMapper);
        ReflectionTestUtils.setField(taskMapper, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(taskMapper, "attachmentMapper", attachmentMapper);

        projectMapper = new ProjectMapperImpl();
        ReflectionTestUtils.setField(projectMapper, "employeeMapper", employeeMapper);
        ReflectionTestUtils.setField(projectMapper, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(projectMapper, "attachmentMapper", attachmentMapper);

        organizationMapper = new OrganizationMapperImpl();
        ReflectionTestUtils.setField(organizationMapper, "employeeMapper", employeeMapper);
        ReflectionTestUtils.setField(organizationMapper, "projectMapper", projectMapper);
        ReflectionTestUtils.setField(organizationMapper, "attachmentMapper", attachmentMapper);

        MaterialMapper materialMapper = new MaterialMapperImpl();
        ReflectionTestUtils.setField(materialMapper, "employeeMapper", employeeMapper);

        IndentItemMapper indentItemMapper = new IndentItemMapperImpl();
        ReflectionTestUtils.setField(indentItemMapper, "materialMapper", materialMapper);

        indentMapper = new IndentMapperImpl();
        ReflectionTestUtils.setField(indentMapper, "employeeMapper", employeeMapper);
        ReflectionTestUtils.setField(indentMapper, "indentItemMapper", indentItemMapper);
    }

    // ------------------------------------------------------------------ projects

    @Test
    void aPageOfProjectsPaysForEveryTaskAndIssueHangingOffIt() {
        wireMappers();
        String tag = fixture(PAGE, 6, 3);

        Cost full = measure(() -> {
            List<ProjectDto> dtos = page(tag).stream().map(projectMapper::toDto).toList();
            assertThat(dtos).hasSize(PAGE);
        });

        Cost summary = measure(() -> {
            List<Project> projects = page(tag);
            ProjectProgressLookup progress = progressFor(projects);
            List<ProjectSummaryDto> dtos = projects.stream()
                    .map(project -> projectMapper.toSummaryDto(project, progress))
                    .toList();
            assertThat(dtos).hasSize(PAGE);
        });

        System.out.printf("projects, page of %d with 6 tasks and 3 issues each%n"
                        + "  full  ProjectDto         : %s%n"
                        + "  summary ProjectSummaryDto: %s%n",
                PAGE, full, summary);

        // Five projects, thirty tasks, ninety issues, plus the employees on each project and each
        // task. The full view materialises all of it to render a table of project names.
        assertThat(full.entitiesLoaded)
                .as("the full view should load the whole graph: %s", full)
                .isGreaterThanOrEqualTo(PAGE + PAGE * 6 + PAGE * 6 * 3);

        // The projection loads the page and nothing else. The averages come back as scalars.
        // The page itself plus the one organization every project on it points at, which is an
        // eager to-one on the entity and so comes back with the page query.
        assertThat(summary.entitiesLoaded)
                .as("the projection should load the page and its own to-one rows, nothing else: %s",
                        summary)
                .isEqualTo(PAGE + 1);

        assertThat(summary.statements)
                .as("the projection should cost fewer round trips: %s against %s", summary, full)
                .isLessThan(full.statements);

    }

    /**
     * The claim worth more than either absolute figure: the projection's cost is a function of the
     * page size alone. Same five projects either way; only the depth of what hangs off them
     * changes. The full view pays for the difference and the projection does not notice it.
     */
    @Test
    void onlyTheFullProjectViewGetsDearerAsTheGraphDeepens() {
        wireMappers();
        String shallow = fixture(PAGE, 2, 1);
        String deep = fixture(PAGE, 8, 4);

        Cost fullShallow = measure(() -> page(shallow).forEach(projectMapper::toDto));
        Cost fullDeep = measure(() -> page(deep).forEach(projectMapper::toDto));
        Cost summaryShallow = measure(() -> summarise(shallow));
        Cost summaryDeep = measure(() -> summarise(deep));

        assertThat(fullDeep.entitiesLoaded)
                .as("a deeper graph must cost the full view more: %s against %s", fullDeep, fullShallow)
                .isGreaterThan(fullShallow.entitiesLoaded);

        assertThat(summaryDeep.entitiesLoaded)
                .as("the projection must not notice the depth at all: %s against %s",
                        summaryDeep, summaryShallow)
                .isEqualTo(summaryShallow.entitiesLoaded);
        assertThat(summaryDeep.statements)
                .as("nor in round trips: %s against %s", summaryDeep, summaryShallow)
                .isEqualTo(summaryShallow.statements);

        System.out.printf("projects, same page of %d, shallow (2 tasks, 1 issue) against deep (8 tasks, 4 issues)%n"
                        + "  full    : %s -> %s%n"
                        + "  summary : %s -> %s%n",
                PAGE, fullShallow, fullDeep, summaryShallow, summaryDeep);
    }

    /**
     * The projection is only worth having if it says the same thing. Progress on the full view is
     * the mean of the project's task progress, computed in memory over the loaded collection; on
     * the summary it comes out of one {@code AVG} over the page. They have to agree, including for
     * a project with no tasks, which the calculator reports as zero and the grouped read reports as
     * no row at all.
     */
    @Test
    void theSummaryReportsTheProgressTheFullViewReports() {
        wireMappers();
        String tag = fixture(PAGE, 3, 0);
        Project childless = persistProject(tag, PAGE);
        em.flush();
        em.clear();

        List<Project> projects = page(tag);
        ProjectProgressLookup progress = progressFor(projects);

        for (Project project : projects) {
            ProjectDto full = projectMapper.toDto(project);
            ProjectSummaryDto summary = projectMapper.toSummaryDto(project, progress);

            assertThat(summary.getProgress())
                    .as("project %d must report the same progress on both views", project.getId())
                    .isEqualTo(full.getProgress());
        }

        assertThat(projects).extracting(Project::getId).contains(childless.getId());
        assertThat(progress.progressOf(childless.getId()))
                .as("a project with no tasks reports zero, as the calculator does")
                .isEqualTo(0.0);
    }

    /**
     * The projection carries every scalar the full view carries. A list projection that quietly
     * drops a field is a worse trade than the graph it saves, and nothing else would catch it.
     */
    @Test
    void theSummaryCarriesEveryScalarTheFullViewCarries() {
        wireMappers();
        String tag = fixture(1, 2, 1);
        Project project = page(tag).get(0);

        ProjectDto full = projectMapper.toDto(project);
        ProjectSummaryDto summary = projectMapper.toSummaryDto(project, progressFor(List.of(project)));

        assertThat(summary.getId()).isEqualTo(full.getId());
        assertThat(summary.getProjectName()).isEqualTo(full.getProjectName());
        assertThat(summary.getProjectAddress()).isEqualTo(full.getProjectAddress());
        assertThat(summary.getProjectCity()).isEqualTo(full.getProjectCity());
        assertThat(summary.getProjectState()).isEqualTo(full.getProjectState());
        assertThat(summary.getProjectPostalCode()).isEqualTo(full.getProjectPostalCode());
        assertThat(summary.getCreatedAt()).isEqualTo(full.getCreatedAt());
        assertThat(summary.getCreatedBy()).isEqualTo(full.getCreatedBy());
        assertThat(summary.getUpdatedAt()).isEqualTo(full.getUpdatedAt());
        assertThat(summary.getUpdatedBy()).isEqualTo(full.getUpdatedBy());
        assertThat(summary.getStatus()).isEqualTo(full.getStatus());
        assertThat(summary.getProjectType()).isEqualTo(full.getProjectType());
        assertThat(summary.getCustomerId()).isEqualTo(full.getCustomerId());
        assertThat(summary.getProjectLatitude()).isEqualTo(full.getProjectLatitude());
        assertThat(summary.getProjectLongitude()).isEqualTo(full.getProjectLongitude());
        assertThat(summary.getStartDate()).isEqualTo(full.getStartDate());
        assertThat(summary.getEndDate()).isEqualTo(full.getEndDate());
        assertThat(summary.getProgress()).isEqualTo(full.getProgress());
    }

    // ------------------------------------------------------------------ organizations

    /**
     * The organization picker. Its response needs a name and a logo, and the full DTO answers it
     * with every project in the organization and everything hanging off each one.
     */
    @Test
    void listingAnOrganizationPullsTheWholeTenantAndTheProjectionDoesNot() {
        wireMappers();
        String tag = fixture(4, 4, 2);
        Organization organization = page(tag).get(0).getOrganization();
        Long organizationId = organization.getId();
        em.flush();
        em.clear();

        Cost full = measure(() -> {
            OrganizationDto dto = organizationMapper.toDto(em.find(Organization.class, organizationId));
            assertThat(dto.getProjects()).hasSize(4);
        });

        Cost summary = measure(() -> {
            OrganizationSimpleDto dto =
                    organizationMapper.toSimpleDto(em.find(Organization.class, organizationId));
            assertThat(dto.getOrganizationName()).isNotNull();
        });

        System.out.printf("organization with 4 projects of 4 tasks and 2 issues each%n"
                        + "  full  OrganizationDto        : %s%n"
                        + "  summary OrganizationSimpleDto: %s%n",
                full, summary);

        assertThat(summary.entitiesLoaded)
                .as("the picker should read one row: %s", summary)
                .isEqualTo(1);
        assertThat(full.entitiesLoaded)
                .as("the full view reads the tenant: %s", full)
                .isGreaterThan(summary.entitiesLoaded * 10);

    }

    // ------------------------------------------------------------------ indents

    @Test
    void aPageOfIndentsPaysForEveryLineAndEveryMaterialOnIt() {
        wireMappers();
        String tag = indentFixture(PAGE, 10);

        Cost full = measure(() -> {
            List<IndentDto> dtos = indents(tag).stream()
                    .map(indent -> indentMapper.toDto(indent, MaterialStockLookup.none()))
                    .toList();
            assertThat(dtos).hasSize(PAGE);
            assertThat(dtos.get(0).getItems()).hasSize(10);
        });

        Cost summary = measure(() -> {
            List<Indent> page = indents(tag);
            IndentItemCountLookup counts = IndentItemCountLookup.of(
                    indentItemRepository.countItemsByIndentIds(page.stream().map(Indent::getId).toList()));
            List<IndentSummaryDto> dtos = page.stream()
                    .map(indent -> indentMapper.toSummaryDto(indent, counts))
                    .toList();
            assertThat(dtos).hasSize(PAGE);
            assertThat(dtos).allSatisfy(dto -> assertThat(dto.getItemCount()).isEqualTo(10));
        });

        System.out.printf("indents, page of %d with 10 lines each%n"
                        + "  full  IndentDto         : %s%n"
                        + "  summary IndentSummaryDto: %s%n",
                PAGE, full, summary);

        // Five indents of ten lines: fifty lines, each carrying a material, plus the raiser of each
        // indent. The stock aggregate the lines then need is on top of this and is not counted here.
        assertThat(full.entitiesLoaded)
                .as("the full view should load every line and every material: %s", full)
                .isGreaterThanOrEqualTo(PAGE + PAGE * 10);
        // The page, the one employee who raised them all, the one project they are all on, and
        // that project's organization. Four rows beyond the page however many lines the indents
        // have, against a hundred and nine.
        assertThat(summary.entitiesLoaded)
                .as("the projection should load the page and its own to-one rows, nothing else: %s",
                        summary)
                .isEqualTo(PAGE + 3);

    }

    @Test
    void theIndentSummaryCountsTheLinesTheFullViewLists() {
        wireMappers();
        String tag = indentFixture(3, 4);
        Indent empty = persistIndent(tag, 3, 0);
        em.flush();
        em.clear();

        List<Indent> page = indents(tag);
        IndentItemCountLookup counts = IndentItemCountLookup.of(
                indentItemRepository.countItemsByIndentIds(page.stream().map(Indent::getId).toList()));

        for (Indent indent : page) {
            IndentDto full = indentMapper.toDto(indent, MaterialStockLookup.none());
            IndentSummaryDto summary = indentMapper.toSummaryDto(indent, counts);

            assertThat(summary.getItemCount())
                    .as("indent %d must count the lines the full view lists", indent.getId())
                    .isEqualTo(full.getItems().size());
            assertThat(summary.getIndentNumber()).isEqualTo(full.getIndentNumber());
            assertThat(summary.getProjectId()).isEqualTo(full.getProjectId());
            assertThat(summary.getProjectName()).isEqualTo(full.getProjectName());
            assertThat(summary.getStatus()).isEqualTo(full.getStatus());
            assertThat(summary.getCreatedById()).isEqualTo(full.getCreatedBy().getId());
            assertThat(summary.getCreatedByName()).isEqualTo(full.getCreatedBy().getEmployeeName());
        }

        assertThat(counts.itemCountOf(empty.getId()))
                .as("an indent with no lines counts zero rather than going missing")
                .isZero();
    }

    // ------------------------------------------------------------------ measuring

    /** One reading of what a conversion cost. */
    private record Cost(long statements, long entitiesLoaded, long collectionsLoaded) {
        @Override
        public String toString() {
            return "%d statements, %d entities, %d collections"
                    .formatted(statements, entitiesLoaded, collectionsLoaded);
        }
    }

    /**
     * Runs a read and reports what it cost, starting from a cold persistence context so nothing
     * already in memory makes the second reading look cheaper than the first.
     *
     * @param read The read to measure.
     * @return Its cost.
     */
    private Cost measure(Runnable read) {
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        boolean wereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            em.flush();
            em.clear();
            statistics.clear();

            read.run();

            return new Cost(statistics.getPrepareStatementCount(),
                    statistics.getEntityLoadCount(),
                    statistics.getCollectionLoadCount());
        } finally {
            statistics.setStatisticsEnabled(wereEnabled);
        }
    }

    private void summarise(String tag) {
        List<Project> projects = page(tag);
        ProjectProgressLookup progress = progressFor(projects);
        projects.forEach(project -> projectMapper.toSummaryDto(project, progress));
    }

    private List<Project> page(String tag) {
        return projectRepository.search("%" + tag + "%",
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "id"))).getContent();
    }

    private ProjectProgressLookup progressFor(List<Project> projects) {
        return ProjectProgressLookup.of(projectRepository.averageTaskProgressByProjectIds(
                projects.stream().map(Project::getId).toList()));
    }

    private List<Indent> indents(String tag) {
        return entityManager.createQuery(
                        "SELECT i FROM Indent i WHERE i.indentNumber LIKE :tag ORDER BY i.id", Indent.class)
                .setParameter("tag", "%" + tag + "%")
                .getResultList();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Persists one organization holding a number of projects, each with the same number of tasks
     * and each task the same number of issues.
     *
     * @param projects Projects to create.
     * @param tasksPerProject Tasks on each project.
     * @param issuesPerTask Issues on each task.
     * @return The tag every one of those project names carries, for reading them back.
     */
    private String fixture(int projects, int tasksPerProject, int issuesPerTask) {
        String tag = "costfixture" + nextSuffix++;
        Organization organization = persistOrganization(tag);
        Employee creator = persistEmployee(organization, tag);

        for (int p = 0; p < projects; p++) {
            Project project = persistProject(organization, tag, p, creator);
            for (int t = 0; t < tasksPerProject; t++) {
                Task task = persistTask(project, creator, organization, tag, p, t);
                for (int i = 0; i < issuesPerTask; i++) {
                    persistIssue(task, creator, organization, tag, p, t, i);
                }
            }
        }
        em.flush();
        em.clear();
        return tag;
    }

    private String indentFixture(int indents, int linesPerIndent) {
        String tag = "costindent" + nextSuffix++;
        Organization organization = persistOrganization(tag);
        Employee creator = persistEmployee(organization, tag);
        Project project = persistProject(organization, tag, 0, creator);

        for (int n = 0; n < indents; n++) {
            persistIndent(organization, project, creator, tag, n, linesPerIndent);
        }
        em.flush();
        em.clear();
        return tag;
    }

    private Indent persistIndent(String tag, int index, int lines) {
        Project project = page(tag).get(0);
        return persistIndent(project.getOrganization(), project,
                entityManager.createQuery("SELECT e FROM Employee e WHERE e.employeeName = :name", Employee.class)
                        .setParameter("name", tag).getSingleResult(),
                tag, index, lines);
    }

    private Indent persistIndent(Organization organization, Project project, Employee creator,
                                 String tag, int index, int lines) {
        Indent indent = new Indent();
        indent.setIndentNumber(tag + "-" + index);
        indent.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        indent.setCreatedBy(creator);
        indent.setProject(project);
        indent.setOrganization(organization);
        indent.setStatus(IndentStatus.PENDING);
        List<IndentItem> items = new ArrayList<>();
        indent.setItems(items);
        em.persist(indent);

        for (int line = 0; line < lines; line++) {
            Material material = new Material();
            material.setSku(tag + "-" + index + "-" + line);
            material.setMaterialName("Material " + index + "-" + line);
            material.setUnit("bags");
            material.setOrganization(organization);
            em.persist(material);

            IndentItem item = new IndentItem();
            item.setIndent(indent);
            item.setMaterial(material);
            item.setRequestedQuantity(5);
            item.setConvertedToPurchaseOrder(false);
            item.setOrganization(organization);
            em.persist(item);
            items.add(item);
        }
        return indent;
    }

    private Organization persistOrganization(String tag) {
        Organization organization = new Organization();
        organization.setOrganizationName("Org " + tag);
        organization.setOrganizationAddress(tag + " address");
        organization.setOrganizationEmail(tag + "@example.test");
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private Employee persistEmployee(Organization organization, String tag) {
        User user = new User();
        user.setKeycloakId("kc-" + tag);
        user.setName(tag);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(organization);
        employee.setUser(user);
        employee.setEmployeeName(tag);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(tag + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }

    private Project persistProject(String tag, int index) {
        List<Project> existing = page(tag);
        Organization organization = existing.get(0).getOrganization();
        return persistProject(organization, tag, index, null);
    }

    private Project persistProject(Organization organization, String tag, int index, Employee member) {
        Project project = new Project();
        project.setProjectName("Project " + tag + " " + index);
        project.setProjectAddress(tag + " site road");
        project.setProjectCity("Chennai");
        project.setOrganization(organization);
        project.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        if (member != null) {
            project.setEmployees(List.of(member));
        }
        em.persist(project);
        return project;
    }

    private Task persistTask(Project project, Employee creator, Organization organization,
                             String tag, int projectIndex, int taskIndex) {
        Task task = new Task();
        task.setTitle("Task %s %d %d".formatted(tag, projectIndex, taskIndex));
        task.setCreator(creator);
        task.setProject(project);
        task.setOrganization(organization);
        task.setStatus(TaskStatus.onGoing);
        task.setProgress(0.1 * (taskIndex + 1));
        task.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        em.persist(task);
        return task;
    }

    private void persistIssue(Task task, Employee creator, Organization organization,
                              String tag, int projectIndex, int taskIndex, int issueIndex) {
        Issue issue = new Issue();
        issue.setTitle("Issue %s %d %d %d".formatted(tag, projectIndex, taskIndex, issueIndex));
        issue.setDescription("raised by the cost fixture");
        issue.setType(IssueType.quality);
        issue.setStatus(IssueStatus.open);
        issue.setTask(task);
        issue.setCreatedBy(creator);
        issue.setOrganization(organization);
        issue.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        issue.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        em.persist(issue);
    }
}
