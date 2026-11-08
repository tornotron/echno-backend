package org.tornotron.echno_backend.vendor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.dto.VendorDto;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;
import org.tornotron.echno_backend.vendor.mapper.VendorMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paging a mobile list in the query, against a real CockroachDB (#999).
 *
 * <p>The vendor search stands in for every list the issue paged: each one now pages in the
 * repository and orders by its module's own field with the id as tie-break. The seed gives three
 * vendors the same name, so an order on the name alone could hand the same row to two pages and
 * skip another. Walking every page and finding each matching vendor exactly once, in name-then-id
 * order, is what shows the order is total.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VendorSearchPagingIT extends AbstractIntegrationTest {

    @Autowired
    private VendorRepository vendorRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private VendorService service;
    private final List<Long> expectedOrder = new ArrayList<>();

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization org = new Organization();
        org.setOrganizationName("Paging Org");
        org.setOrganizationAddress("Paging Org address");
        org.setOrganizationEmail("pagingorg@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        // Seven matches: two names, the second shared by three vendors, plus one that must not match.
        List<Vendor> matches = new ArrayList<>();
        for (String name : List.of("Cement Depot", "Cement Depot", "Cement Depot",
                "Brick Cement Traders", "Brick Cement Traders", "Delta Cement", "Alpha Cement")) {
            matches.add(persist(org, name));
        }
        persist(org, "Steel Yard");
        entityManager.flush();
        entityManager.clear();

        matches.stream()
                .sorted((a, b) -> {
                    int byName = a.getVendorName().compareTo(b.getVendorName());
                    return byName != 0 ? byName : a.getId().compareTo(b.getId());
                })
                .forEach(v -> expectedOrder.add(v.getId()));

        VendorMapper mapper = mock(VendorMapper.class);
        when(mapper.toDto(any(Vendor.class))).thenAnswer(call -> {
            Vendor vendor = call.getArgument(0);
            VendorDto dto = new VendorDto();
            dto.setId(vendor.getId());
            dto.setVendorName(vendor.getVendorName());
            return dto;
        });
        service = new VendorService(vendorRepository, null, mapper, null);
        TenantContext.setCurrentOrgId(org.getId());
        // Other suites share this database, so scope the reads to this organization the way the
        // request filter does in the running application.
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", org.getId());
    }

    @AfterEach
    void clear() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    private Vendor persist(Organization org, String name) {
        Vendor vendor = new Vendor();
        vendor.setVendorName(name);
        vendor.setType(VendorType.MATERIALS);
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setVendorEmail(name.replace(" ", "").toLowerCase() + System.nanoTime() + "@example.test");
        vendor.setOrganization(org);
        entityManager.persist(vendor);
        return vendor;
    }

    @Test
    void theFirstPageCarriesTheTotalsAndSaysMoreFollow() {
        Page<VendorDto> first = service.searchVendorsByName("cement", 0, 3);

        assertThat(first.getContent()).extracting(VendorDto::getId).containsExactlyElementsOf(expectedOrder.subList(0, 3));
        assertThat(first.getNumber()).isZero();
        assertThat(first.getTotalElements()).isEqualTo(7);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.isLast()).isFalse();
    }

    @Test
    void aMiddlePageIsNeitherFirstNorLast() {
        Page<VendorDto> middle = service.searchVendorsByName("cement", 1, 3);

        assertThat(middle.getContent()).extracting(VendorDto::getId).containsExactlyElementsOf(expectedOrder.subList(3, 6));
        assertThat(middle.getNumber()).isEqualTo(1);
        assertThat(middle.isFirst()).isFalse();
        assertThat(middle.isLast()).isFalse();
    }

    @Test
    void theLastPageHoldsTheRemainderAndSaysSo() {
        Page<VendorDto> last = service.searchVendorsByName("cement", 2, 3);

        assertThat(last.getContent()).extracting(VendorDto::getId).containsExactly(expectedOrder.get(6));
        assertThat(last.getNumber()).isEqualTo(2);
        assertThat(last.getTotalElements()).isEqualTo(7);
        assertThat(last.isLast()).isTrue();
    }

    @Test
    void walkingEveryPageVisitsEachMatchOnceInATotalOrder() {
        List<Long> seen = new ArrayList<>();
        int pageNo = 0;
        Page<VendorDto> page;
        do {
            page = service.searchVendorsByName("cement", pageNo++, 2);
            page.getContent().forEach(dto -> seen.add(dto.getId()));
        } while (!page.isLast());

        assertThat(seen)
                .as("every vendor whose name matches, once each, ties on the name broken by id")
                .containsExactlyElementsOf(expectedOrder);
    }

    @Test
    void thePageIsTheSameEveryTimeItIsAskedFor() {
        Page<VendorDto> once = service.searchVendorsByName("cement", 1, 2);
        Page<VendorDto> again = service.searchVendorsByName("cement", 1, 2);

        assertThat(again.getContent()).extracting(VendorDto::getId)
                .containsExactlyElementsOf(once.getContent().stream().map(VendorDto::getId).toList());
    }
}
