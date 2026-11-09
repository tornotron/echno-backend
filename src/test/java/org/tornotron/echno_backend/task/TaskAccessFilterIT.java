package org.tornotron.echno_backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.enums.TaskStatus;

/**
 * The task query admits exactly the tasks {@link AccessFilter#admits} does, on CockroachDB, so a list
 * and a check on one task can never disagree, and a task with several assignees is one row of a page.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private TaskRepository tasks;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee me;
    private List<Task> all;

    @BeforeEach
    void someTasks() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        me = fixtures.employee(mine, "me-" + System.nanoTime());
        Employee someoneElse = fixtures.employee(mine, "manager-" + System.nanoTime());
        Employee another = fixtures.employee(mine, "another-" + System.nanoTime());
        Employee theirs1 = fixtures.employee(theirs, "their-manager-" + System.nanoTime());
        all = List.of(
                task(mine, "On first", first, someoneElse, Set.of()),
                task(mine, "On second", second, someoneElse, Set.of(another)),
                task(mine, "Created by me on second", second, me, Set.of()),
                task(mine, "Assigned to me and another on second", second, someoneElse, Set.of(me, another)),
                task(mine, "Assigned to me on first", first, someoneElse, Set.of(me)),
                // Another organization's task, on a project whose rows a filter of mine could be confused with.
                task(theirs, "Theirs", theirProject, theirs1, Set.of()));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameTasksInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, owned(), null),
                "assigned to them", new AccessFilter(org, false, null, assigned(), null),
                "assigned to them on one project", new AccessFilter(org, false, null, null,
                        Map.of(second.getId(), assigned())),
                "a vendor, which no task has", new AccessFilter(org, false, null,
                        new Links(null, null, null, Set.of(me.getId())), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), owned(),
                        Map.of(second.getId(), assigned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(task -> filter.admits(TaskAccess.facts(task)))
                    .map(Task::getTitle)
                    .collect(Collectors.toSet());
            Page<Task> page = tasks.findAll(TaskAccess.admittedBy(filter), PageRequest.of(0, 50));
            List<String> listed = page.stream().map(Task::getTitle).toList();

            assertThat(new HashSet<>(listed)).as(name).isEqualTo(expected);
            assertThat(listed).as("%s lists each task once", name).doesNotHaveDuplicates();
            assertThat(page.getTotalElements()).as("%s counts each task once", name).isEqualTo(expected.size());
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), owned(),
                Map.of(second.getId(), assigned()));
        assertThat(all.stream().filter(task -> mix.admits(TaskAccess.facts(task))).map(Task::getTitle))
                .containsExactlyInAnyOrder("On first", "Created by me on second",
                        "Assigned to me and another on second", "Assigned to me on first");
    }

    private Links owned() {
        return new Links(Set.of(me.getId()), null, null, null);
    }

    private Links assigned() {
        return new Links(null, Set.of(me.getId()), null, null);
    }

    /** A task named by its title. */
    private Task task(Organization org, String title, Project project, Employee creator, Set<Employee> assignees) {
        Task task = new Task();
        task.setTitle(title);
        task.setStatus(TaskStatus.upcoming);
        task.setCreator(creator);
        task.setProject(project);
        task.setOrganization(org);
        task.setAssignees(new HashSet<>(assignees));
        em.persist(task);
        return task;
    }
}
