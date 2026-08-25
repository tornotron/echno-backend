package org.tornotron.echno_backend.expense;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ExpenseRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup and that the
 * paginated search runs on its null-search path, which is the one that used to break on
 * CockroachDB when the pattern was built with SQL {@code ||}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExpenseRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_returnsTheExpense() {
        Organization org = persistOrganization("Org A");
        Expense expense = persistExpense(org, "EXP-2027-000001", "Cement for slab", "pending");
        em.flush();
        em.clear();

        Optional<Expense> found = expenseRepository.findByIdAndOrganization_Id(expense.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getDescription()).isEqualTo("Cement for slab");
        assertThat(found.get().getExpenseNumber()).isEqualTo("EXP-2027-000001");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
    }

    @Test
    void findByIdAndOrganization_doesNotReturnAcrossTenants() {
        Organization orgA = persistOrganization("Org A2");
        Organization orgB = persistOrganization("Org B2");
        Expense expense = persistExpense(orgA, "EXP-2027-000002", "Diesel for excavator", "approved");
        em.flush();
        em.clear();

        Optional<Expense> found = expenseRepository.findByIdAndOrganization_Id(expense.getId(), orgB.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void search_withNullSearchAndStatus_includesThePersistedExpense() {
        Organization org = persistOrganization("Org C");
        persistExpense(org, "EXP-2027-000003", "Scaffolding rental", "paid");
        em.flush();
        em.clear();

        // The no-filter path: both binds are null, so the query takes its IS NULL branch.
        // This is the case that failed on CockroachDB when the LIKE pattern was assembled
        // with SQL CONCAT/||, so exercising it here keeps that regression covered.
        Page<Expense> result = expenseRepository.search(null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Expense::getExpenseNumber)
                .contains("EXP-2027-000003");
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

    private Expense persistExpense(Organization org, String number, String description, String status) {
        Expense expense = new Expense();
        expense.setOrganization(org);
        expense.setExpenseNumber(number);
        expense.setType("direct");
        expense.setCategory("materials");
        expense.setStatus(status);
        expense.setDescription(description);
        expense.setAmount(new BigDecimal("45000.00"));
        expense.setCurrency("INR");
        expense.setExpenseDate(LocalDate.of(2026, 8, 20));
        em.persist(expense);
        return expense;
    }
}
