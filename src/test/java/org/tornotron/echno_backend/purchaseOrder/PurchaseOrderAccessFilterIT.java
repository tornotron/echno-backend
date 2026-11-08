package org.tornotron.echno_backend.purchaseOrder;

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
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * The order and line queries admit exactly the orders {@link AccessFilter#admits} does, on
 * CockroachDB, so a list and a check on one order can never disagree. A line follows its order.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PurchaseOrderAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private PurchaseOrderRepository orders;

    @Autowired
    private PurchaseOrderItemRepository lines;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee owner;
    private Vendor vendor;
    private List<PurchaseOrder> all;

    @BeforeEach
    void someOrders() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        owner = fixtures.employee(mine, "owner-" + System.nanoTime());
        Employee someoneElse = fixtures.employee(mine, "buyer-" + System.nanoTime());
        Employee theirBuyer = fixtures.employee(theirs, "their-buyer-" + System.nanoTime());
        vendor = fixtures.vendor(mine, "Supplier " + System.nanoTime());
        Vendor otherVendor = fixtures.vendor(mine, "Other supplier " + System.nanoTime());
        Vendor theirVendor = fixtures.vendor(theirs, "Their supplier " + System.nanoTime());
        Material material = material(mine);
        Material theirMaterial = material(theirs);
        all = List.of(
                order(mine, "On first", first, someoneElse, otherVendor, material),
                order(mine, "On second", second, someoneElse, otherVendor, material),
                order(mine, "Raised by the owner on second", second, owner, otherVendor, material),
                order(mine, "Placed with the vendor, no project", null, someoneElse, vendor, material),
                order(mine, "Placed with the vendor on second", second, someoneElse, vendor, material),
                order(mine, "No project", null, someoneElse, otherVendor, material),
                // Another organization's order, on a project whose rows a filter of mine could be confused with.
                order(theirs, "Theirs", theirProject, theirBuyer, theirVendor, theirMaterial));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameOrdersInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, owned(), null),
                "their vendor", new AccessFilter(org, false, null, supplied(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), owned())),
                "their vendor on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), supplied())),
                "a related employee, which no order has", new AccessFilter(org, false, null,
                        new Links(null, Set.of(owner.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), supplied(),
                        Map.of(second.getId(), owned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(order -> filter.admits(PurchaseOrderAccess.facts(order)))
                    .map(PurchaseOrder::getRemarks)
                    .collect(Collectors.toSet());
            Set<String> listed = orders.findAll(PurchaseOrderAccess.admittedBy(filter), PageRequest.of(0, 50))
                    .stream()
                    .map(PurchaseOrder::getRemarks)
                    .collect(Collectors.toSet());
            Set<String> lined = lines.findAll(PurchaseOrderAccess.linesAdmittedBy(filter)).stream()
                    .map(PurchaseOrderItem::getRemarks)
                    .collect(Collectors.toSet());

            assertThat(listed).as(name).isEqualTo(expected);
            assertThat(lined).as(name).isEqualTo(expected);
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), supplied(),
                Map.of(second.getId(), owned()));
        assertThat(all.stream().filter(order -> mix.admits(PurchaseOrderAccess.facts(order)))
                .map(PurchaseOrder::getRemarks))
                .containsExactlyInAnyOrder("On first", "Raised by the owner on second",
                        "Placed with the vendor, no project", "Placed with the vendor on second");
    }

    private Links owned() {
        return new Links(Set.of(owner.getId()), null, null, null);
    }

    private Links supplied() {
        return new Links(null, null, null, Set.of(vendor.getId()));
    }

    private Material material(Organization org) {
        Material material = new Material();
        material.setMaterialName("Cement");
        material.setUnit("bag");
        material.setOrganization(org);
        em.persist(material);
        return material;
    }

    /** An order named by its remarks, with one line named the same way. */
    private PurchaseOrder order(Organization org, String name, Project project, Employee creator,
                                Vendor supplier, Material material) {
        PurchaseOrder order = new PurchaseOrder();
        order.setPoNumber("PO-" + System.nanoTime());
        order.setRemarks(name);
        order.setVendor(supplier);
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setCreatedBy(creator);
        order.setProject(project);
        order.setOrganization(org);
        order.setTotalAmount(BigDecimal.ZERO);
        em.persist(order);
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setPurchaseOrder(order);
        line.setMaterial(material);
        line.setOrderedQuantity(1);
        line.setReceivedQuantity(0);
        line.setRemarks(name);
        line.setOrganization(org);
        em.persist(line);
        return order;
    }
}
