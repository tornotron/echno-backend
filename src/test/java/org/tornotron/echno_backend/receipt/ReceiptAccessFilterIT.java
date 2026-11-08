package org.tornotron.echno_backend.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The receipt listing admits exactly the receipts {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one receipt can never disagree, and its search and status conditions narrow it as
 * the unrestricted search does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Customer carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import(JpaAuditingConfig.class)
class ReceiptAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private ReceiptRepository receipts;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee issuer;
    private Customer customer;
    private List<Receipt> all;
    private String run;
    private int numbered;

    @BeforeEach
    void someReceipts() {
        AccessFixtures fixtures = new AccessFixtures(em);
        run = Long.toString(System.nanoTime());
        mine = fixtures.organization("Filter " + run);
        Organization theirs = fixtures.organization("Filter other " + run);
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        issuer = fixtures.employee(mine, "issuer-" + run);
        customer = fixtures.customer(mine, "C" + run.substring(run.length() - 8));
        all = List.of(
                receipt(mine, "On first", "issued", first, null, null),
                receipt(mine, "On second", "draft", second, null, null),
                receipt(mine, "Issued by them on second", "issued", second, issuer.getId(), null),
                receipt(mine, "From the customer, no project", "issued", null, null, customer.getId()),
                receipt(mine, "From the customer on second", "draft", second, null, customer.getId()),
                receipt(mine, "Sundry", "issued", null, null, null),
                // Another organization's receipt, linked to the same ids it could be confused with.
                receipt(theirs, "Theirs", "issued", theirProject, issuer.getId(), customer.getId()));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameReceiptsInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, issued(), null),
                "their customer", new AccessFilter(org, false, null, client(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), issued())),
                "customer on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), client())),
                "a vendor, which no receipt names", new AccessFilter(org, false, null,
                        new Links(null, null, null, Set.of(issuer.getId())), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), client(),
                        Map.of(second.getId(), issued())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(receipt -> filter.admits(ReceiptAccess.facts(receipt)))
                    .map(Receipt::getReceivedFrom)
                    .collect(Collectors.toSet());

            assertThat(listed(filter, null, null)).as(name).isEqualTo(expected).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), client(),
                Map.of(second.getId(), issued()));
        assertThat(all.stream().filter(receipt -> mix.admits(ReceiptAccess.facts(receipt))).map(Receipt::getReceivedFrom))
                .containsExactlyInAnyOrder("On first", "Issued by them on second", "From the customer, no project",
                        "From the customer on second");
    }

    @Test
    void theNarrowedListingSearchesAsTheUnrestrictedOneDoes() {
        AccessFilter everything = AccessFilter.all(mine.getId());
        for (String search : new String[] {null, "%second%", "%customer%", "%rcp-" + run + "-3%"}) {
            for (String status : new String[] {null, "issued", "draft"}) {
                Set<String> searched = receipts.search(search, status, PageRequest.of(0, 500)).stream()
                        .filter(receipt -> receipt.getOrganization().getId().equals(mine.getId()))
                        .map(Receipt::getReceivedFrom)
                        .collect(Collectors.toSet());

                assertThat(listed(everything, search, status)).as("%s %s", search, status).isEqualTo(searched);
            }
        }
        assertThat(listed(everything, "%customer%", "issued")).containsExactly("From the customer, no project");
    }

    private Set<String> listed(AccessFilter filter, String search, String status) {
        return receipts.findAll(ReceiptAccess.admittedBy(filter).and(ReceiptRepository.matching(search, status)),
                        PageRequest.of(0, 500)).stream()
                .map(Receipt::getReceivedFrom)
                .collect(Collectors.toSet());
    }

    private Links issued() {
        return new Links(Set.of(issuer.getId()), null, null, null);
    }

    private Links client() {
        return new Links(null, null, Set.of(customer.getId()), null);
    }

    private Receipt receipt(Organization org, String receivedFrom, String status, Project project, Long issuedBy,
                            UUID customerId) {
        Receipt receipt = new Receipt();
        receipt.setOrganization(org);
        receipt.setReceiptNumber("RCP-" + run + "-" + ++numbered);
        receipt.setReceivedFrom(receivedFrom);
        receipt.setStatus(status);
        receipt.setAmount(new BigDecimal("100.00"));
        receipt.setProjectId(project == null ? null : project.getId());
        receipt.setIssuedBy(issuedBy);
        receipt.setCustomerId(customerId);
        em.persist(receipt);
        return receipt;
    }
}
