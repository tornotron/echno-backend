package org.tornotron.echno_backend.expense;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * The expense listing admits exactly the expenses {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one expense can never disagree, and its search and status conditions narrow it as
 * the unrestricted search does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExpenseAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private ExpenseRepository expenses;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee incurredBy;
    private Vendor vendor;
    private List<Expense> all;
    private String run;
    private int numbered;

    @BeforeEach
    void someExpenses() {
        AccessFixtures fixtures = new AccessFixtures(em);
        run = Long.toString(System.nanoTime());
        mine = fixtures.organization("Filter " + run);
        Organization theirs = fixtures.organization("Filter other " + run);
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        incurredBy = fixtures.employee(mine, "incurred-" + run);
        vendor = fixtures.vendor(mine, "Supplier " + run);
        all = List.of(
                expense(mine, "On first", "paid", first, null, null),
                expense(mine, "On second", "pending", second, null, null),
                expense(mine, "Incurred on second", "paid", second, incurredBy.getId(), null),
                expense(mine, "Paid to the supplier, no project", "paid", null, null, vendor.getId()),
                expense(mine, "Paid to the supplier on second", "pending", second, null, vendor.getId()),
                expense(mine, "Overheads", "paid", null, null, null),
                // Another organization's expense, linked to the same ids it could be confused with.
                expense(theirs, "Theirs", "paid", theirProject, incurredBy.getId(), vendor.getId()));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameExpensesInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, owned(), null),
                "their vendor", new AccessFilter(org, false, null, supplied(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), owned())),
                "vendor on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), supplied())),
                "an assignee, which no expense names", new AccessFilter(org, false, null,
                        new Links(null, Set.of(incurredBy.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), supplied(),
                        Map.of(second.getId(), owned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(expense -> filter.admits(ExpenseAccess.facts(expense)))
                    .map(Expense::getDescription)
                    .collect(Collectors.toSet());

            assertThat(listed(filter, null, null)).as(name).isEqualTo(expected).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), supplied(),
                Map.of(second.getId(), owned()));
        assertThat(all.stream().filter(expense -> mix.admits(ExpenseAccess.facts(expense))).map(Expense::getDescription))
                .containsExactlyInAnyOrder("On first", "Incurred on second", "Paid to the supplier, no project",
                        "Paid to the supplier on second");
    }

    @Test
    void theNarrowedListingSearchesAsTheUnrestrictedOneDoes() {
        AccessFilter everything = AccessFilter.all(mine.getId());
        for (String search : new String[] {null, "%second%", "%supplier%", "%exp-" + run + "-3%"}) {
            for (String status : new String[] {null, "paid", "pending"}) {
                Set<String> searched = expenses.search(search, status, PageRequest.of(0, 500)).stream()
                        .filter(expense -> expense.getOrganization().getId().equals(mine.getId()))
                        .map(Expense::getDescription)
                        .collect(Collectors.toSet());

                assertThat(listed(everything, search, status)).as("%s %s", search, status).isEqualTo(searched);
            }
        }
        assertThat(listed(everything, "%supplier%", "paid")).containsExactly("Paid to the supplier, no project");
    }

    private Set<String> listed(AccessFilter filter, String search, String status) {
        return expenses.findAll(ExpenseAccess.admittedBy(filter).and(ExpenseRepository.matching(search, status)),
                        PageRequest.of(0, 500)).stream()
                .map(Expense::getDescription)
                .collect(Collectors.toSet());
    }

    private Links owned() {
        return new Links(Set.of(incurredBy.getId()), null, null, null);
    }

    private Links supplied() {
        return new Links(null, null, null, Set.of(vendor.getId()));
    }

    private Expense expense(Organization org, String description, String status, Project project, Long employeeId,
                            Long vendorId) {
        Expense expense = new Expense();
        expense.setOrganization(org);
        expense.setExpenseNumber("EXP-" + run + "-" + ++numbered);
        expense.setDescription(description);
        expense.setStatus(status);
        expense.setAmount(new BigDecimal("100.00"));
        expense.setProjectId(project == null ? null : project.getId());
        expense.setEmployeeId(employeeId);
        expense.setVendorId(vendorId);
        em.persist(expense);
        return expense;
    }
}
