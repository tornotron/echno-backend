package org.tornotron.echno_backend.payable;

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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * The payable listings admit exactly the payables {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one payable can never disagree, and the vendor and outstanding conditions narrow
 * them as the unrestricted queries do.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Vendor carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import(JpaAuditingConfig.class)
class PayableAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private PayableRepository payables;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee raisedBy;
    private Vendor vendor;
    private List<Payable> all;
    private String run;
    private int numbered;

    @BeforeEach
    void somePayables() {
        AccessFixtures fixtures = new AccessFixtures(em);
        run = Long.toString(System.nanoTime());
        mine = fixtures.organization("Filter " + run);
        Organization theirs = fixtures.organization("Filter other " + run);
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        raisedBy = fixtures.employee(mine, "raised-" + run);
        vendor = fixtures.vendor(mine, "Supplier " + run);
        all = List.of(
                payable(mine, "On first", "0.00", first, null, null),
                payable(mine, "Paid on second", "100.00", second, null, null),
                payable(mine, "Raised on second", "0.00", second, raisedBy, null),
                payable(mine, "Owed to the supplier, no project", "0.00", null, null, vendor),
                payable(mine, "Owed to the supplier on second", "100.00", second, null, vendor),
                payable(mine, "Overheads", "0.00", null, null, null),
                // Another organization's payable, linked to the same employee and vendor it could be confused with.
                payable(theirs, "Theirs", "0.00", theirProject, raisedBy, vendor));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSamePayablesInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, owned(), null),
                "their vendor", new AccessFilter(org, false, null, supplied(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), owned())),
                "vendor on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), supplied())),
                "an assignee, which no payable names", new AccessFilter(org, false, null,
                        new Links(null, Set.of(raisedBy.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), supplied(),
                        Map.of(second.getId(), owned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(payable -> filter.admits(PayableAccess.facts(payable)))
                    .map(Payable::getContractorName)
                    .collect(Collectors.toSet());

            assertThat(listed(PayableAccess.admittedBy(filter))).as(name).isEqualTo(expected)
                    .doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), supplied(),
                Map.of(second.getId(), owned()));
        assertThat(all.stream().filter(payable -> mix.admits(PayableAccess.facts(payable))).map(Payable::getContractorName))
                .containsExactlyInAnyOrder("On first", "Raised on second", "Owed to the supplier, no project",
                        "Owed to the supplier on second");
    }

    @Test
    void theNarrowedVendorAndOutstandingListingsMatchTheUnrestrictedQueries() {
        Specification<Payable> everything = PayableAccess.admittedBy(AccessFilter.all(mine.getId()));

        Set<String> owedTo = names(payables.findByVendorIdAndOrganization_id(vendor.getId(), mine.getId()));
        assertThat(listed(everything.and(PayableRepository.owedTo(vendor.getId())))).isEqualTo(owedTo)
                .containsExactlyInAnyOrder("Owed to the supplier, no project", "Owed to the supplier on second");

        Set<String> outstanding = payables.findOutstandingPayables().stream()
                .filter(payable -> payable.getOrganization().getId().equals(mine.getId()))
                .map(Payable::getContractorName)
                .collect(Collectors.toSet());
        assertThat(listed(everything.and(PayableRepository.outstanding()))).isEqualTo(outstanding)
                .containsExactlyInAnyOrder("On first", "Raised on second", "Owed to the supplier, no project",
                        "Overheads");
    }

    @Test
    void thePagedListingIsNarrowedInTheQuery() {
        AccessFilter onFirst = new AccessFilter(mine.getId(), false, Set.of(first.getId()), null, null);

        assertThat(payables.findAll(PayableAccess.admittedBy(onFirst), PageRequest.of(0, 500)).getTotalElements())
                .isEqualTo(1);
    }

    private Set<String> listed(Specification<Payable> condition) {
        return names(payables.findAll(condition));
    }

    private static Set<String> names(List<Payable> payables) {
        return payables.stream().map(Payable::getContractorName).collect(Collectors.toSet());
    }

    private Links owned() {
        return new Links(Set.of(raisedBy.getId()), null, null, null);
    }

    private Links supplied() {
        return new Links(null, null, null, Set.of(vendor.getId()));
    }

    private Payable payable(Organization org, String contractor, String paid, Project project, Employee createdBy,
                            Vendor owedTo) {
        Payable payable = new Payable();
        payable.setOrganization(org);
        payable.setPayableNumber("PAY-" + run + "-" + ++numbered);
        payable.setContractorName(contractor);
        payable.setAmountRecorded(new BigDecimal("100.00"));
        payable.setAmountPaid(new BigDecimal(paid));
        payable.setProject(project);
        payable.setCreatedBy(createdBy);
        payable.setVendor(owedTo);
        em.persist(payable);
        return payable;
    }
}
