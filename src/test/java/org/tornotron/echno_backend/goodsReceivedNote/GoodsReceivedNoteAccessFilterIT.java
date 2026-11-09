package org.tornotron.echno_backend.goodsReceivedNote;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
 * The receipt query admits exactly the receipts {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one receipt can never disagree.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GoodsReceivedNoteAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private GoodsReceivedNoteRepository receipts;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee receiver;
    private Vendor vendor;
    private List<GoodsReceivedNote> all;

    @BeforeEach
    void someReceipts() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        receiver = fixtures.employee(mine, "receiver-" + System.nanoTime());
        Employee someoneElse = fixtures.employee(mine, "storekeeper-" + System.nanoTime());
        Employee theirReceiver = fixtures.employee(theirs, "their-receiver-" + System.nanoTime());
        vendor = fixtures.vendor(mine, "Supplier " + System.nanoTime());
        Vendor otherVendor = fixtures.vendor(mine, "Other supplier " + System.nanoTime());
        Vendor theirVendor = fixtures.vendor(theirs, "Their supplier " + System.nanoTime());
        all = List.of(
                receipt(mine, "On first", first, someoneElse, otherVendor),
                receipt(mine, "On second", second, someoneElse, otherVendor),
                receipt(mine, "Received by the receiver on second", second, receiver, otherVendor),
                receipt(mine, "From the vendor, no project", null, someoneElse, vendor),
                receipt(mine, "From the vendor on second", second, someoneElse, vendor),
                receipt(mine, "No project", null, someoneElse, otherVendor),
                // Another organization's receipt, on a project whose rows a filter of mine could be confused with.
                receipt(theirs, "Theirs", theirProject, theirReceiver, theirVendor));
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
                "their own", new AccessFilter(org, false, null, owned(), null),
                "their vendor", new AccessFilter(org, false, null, supplied(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), owned())),
                "their vendor on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), supplied())),
                "a related employee, which no receipt has", new AccessFilter(org, false, null,
                        new Links(null, Set.of(receiver.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), supplied(),
                        Map.of(second.getId(), owned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(receipt -> filter.admits(GoodsReceivedNoteAccess.facts(receipt)))
                    .map(GoodsReceivedNote::getInvoiceNumber)
                    .collect(Collectors.toSet());
            Set<String> listed = receipts.findAll(GoodsReceivedNoteAccess.admittedBy(filter), PageRequest.of(0, 50))
                    .stream()
                    .map(GoodsReceivedNote::getInvoiceNumber)
                    .collect(Collectors.toSet());

            assertThat(listed).as(name).isEqualTo(expected);
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), supplied(),
                Map.of(second.getId(), owned()));
        assertThat(all.stream().filter(receipt -> mix.admits(GoodsReceivedNoteAccess.facts(receipt)))
                .map(GoodsReceivedNote::getInvoiceNumber))
                .containsExactlyInAnyOrder("On first", "Received by the receiver on second",
                        "From the vendor, no project", "From the vendor on second");
    }

    private Links owned() {
        return new Links(Set.of(receiver.getId()), null, null, null);
    }

    private Links supplied() {
        return new Links(null, null, null, Set.of(vendor.getId()));
    }

    /** A receipt named by its invoice number. */
    private GoodsReceivedNote receipt(Organization org, String name, Project project, Employee receivedBy,
                                      Vendor supplier) {
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber("GRN-" + System.nanoTime());
        grn.setReceivedOn(LocalDateTime.of(2026, 11, 1, 10, 0));
        grn.setInvoiceNumber(name);
        grn.setVendor(supplier);
        grn.setReceivedBy(receivedBy);
        grn.setProject(project);
        grn.setOrganization(org);
        em.persist(grn);
        return grn;
    }
}
