package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgTradeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.TradeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.OrgTradeRepository;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trade catalogue and its per-organization copy. Every test here fails without the
 * production code it names: the lazy copy, its idempotency, the enum shim round trip, the
 * five new trades, org-defined trades and tenant isolation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TradeService.class, TradeMapperImpl.class,
        ElementTypeService.class, ElementTypeMapperImpl.class, TenantEntityHelper.class})
class TradeServiceIT extends AbstractIntegrationTest {

    private static final Set<String> NEW_TRADES =
            Set.of("tiling", "painting", "ceilings", "doors-windows", "fire-systems");

    @Autowired
    private TradeService service;

    @Autowired
    private OrgTradeRepository orgTradeRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Trade Org A");
            Organization orgB = persistOrganization("Trade Org B");
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void catalogue_holdsTheSixteenEnumSlugsAndTheFiveNewTradesInSevenGroups() {
        List<TradeCatalogueDto> catalogue = service.listCatalogue();

        Set<String> codes = catalogue.stream().map(TradeCatalogueDto::code).collect(Collectors.toSet());
        for (InspectionTrade legacy : InspectionTrade.values()) {
            assertThat(codes).as("catalogue carries enum slug %s", legacy.getValue()).contains(legacy.getValue());
        }
        assertThat(codes).containsAll(NEW_TRADES);
        assertThat(catalogue).hasSize(21);
        assertThat(catalogue).extracting(TradeCatalogueDto::groupCode)
                .containsOnly("structural", "masonry", "finishes", "openings", "mep", "fire", "general");
    }

    @Test
    void firstRead_copiesTheCatalogueIntoTheOrganizationOnceOnly() {
        assertThat(orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgAId)).isEmpty();

        List<OrgTradeDto> first = service.listOrgTrades(false);
        assertThat(first).hasSize(21);
        assertThat(first).allSatisfy(t -> {
            assertThat(t.catalogueCode()).isEqualTo(t.code());
            assertThat(t.active()).isTrue();
        });

        // a second read, and an explicit top-up, add nothing
        assertThat(service.ensureOrgTrades(entityManager.find(Organization.class, orgAId))).isZero();
        assertThat(service.listOrgTrades(false)).hasSize(21);
        assertThat(orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgAId)).hasSize(21);

        // and the other tenant is untouched until it reads
        assertThat(orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgBId)).isEmpty();
    }

    @Test
    void shim_roundTripsEveryEnumConstantThroughTheOrgRow() {
        for (InspectionTrade legacy : InspectionTrade.values()) {
            OrgTrade row = service.resolve(legacy.getValue(), null);
            assertThat(row.getCode()).isEqualTo(legacy.getValue());
            assertThat(row.getLegacyEnum()).isEqualTo(legacy.name());
            assertThat(row.legacyTrade()).isSameAs(legacy);
            // resolving by id lands on the same row
            assertThat(service.resolve(null, row.getId()).getId()).isEqualTo(row.getId());
        }
    }

    @Test
    void theFiveNewTrades_resolveWithNoEnumConstantBehindThem() {
        for (String code : NEW_TRADES) {
            OrgTrade row = service.resolve(code, null);
            assertThat(row.getCode()).isEqualTo(code);
            assertThat(row.getLegacyEnum()).isNull();
            assertThat(row.legacyTrade()).isNull();
            assertThat(InspectionTrade.find(code)).isEmpty();
        }
        assertThat(service.resolve("fire-systems", null).getGroupCode()).isEqualTo("fire");
    }

    @Test
    void resolve_isCaseInsensitiveOnTheSlugAndRefusesAnUnknownOne() {
        assertThat(service.resolve("Reinforcement", null).getCode()).isEqualTo("reinforcement");
        assertThat(service.resolve(null, null)).isNull();
        assertThat(service.resolve("  ", null)).isNull();
        assertThatThrownBy(() -> service.resolve("precast-erection", null))
                .isInstanceOf(UnprocessableRequestException.class)
                .hasMessageContaining("precast-erection");
    }

    @Test
    void create_addsAnOrgDefinedTradeThatResolvesAndCannotBeDuplicated() {
        OrgTradeDto created = service.create(new CreateTradeRequest(
                "precast-erection", "Precast erection", "structural", "Erection of precast elements", null));

        assertThat(created.catalogueCode()).isNull();
        assertThat(created.sortOrder()).isEqualTo(1000);
        assertThat(service.resolve("precast-erection", null).getId()).isEqualTo(created.id());
        assertThat(service.listOrgTrades(false)).hasSize(22);

        assertThatThrownBy(() -> service.create(new CreateTradeRequest(
                "precast-erection", "Again", "structural", null, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void update_renamesAndDeactivatesButNeverChangesTheCode() {
        OrgTradeDto rcc = service.listOrgTrades(false).stream()
                .filter(t -> t.code().equals("rcc")).findFirst().orElseThrow();

        OrgTradeDto updated = service.update(rcc.id(),
                new UpdateTradeRequest("Concrete", "structural-works", null, 5, false));

        assertThat(updated.code()).isEqualTo("rcc");
        assertThat(updated.name()).isEqualTo("Concrete");
        assertThat(updated.groupCode()).isEqualTo("structural-works");
        assertThat(updated.sortOrder()).isEqualTo(5);
        assertThat(updated.active()).isFalse();
        // gone from the default picker, still there when asked for, still resolvable
        assertThat(service.listOrgTrades(false)).extracting(OrgTradeDto::code).doesNotContain("rcc");
        assertThat(service.listOrgTrades(true)).extracting(OrgTradeDto::code).contains("rcc");
        assertThat(service.resolve("rcc", null).getId()).isEqualTo(rcc.id());
    }

    @Test
    void trades_areScopedToTheOwningTenant() {
        OrgTradeDto custom = service.create(new CreateTradeRequest(
                "facade-glazing", "Facade glazing", "openings", null, null));
        entityManager.flush();
        entityManager.clear();

        TenantContext.setCurrentOrgId(orgBId);
        enableOrgFilter(orgBId);
        // B's own copy does not carry A's custom trade, and A's row id is not B's to use
        assertThat(service.listOrgTrades(true)).extracting(OrgTradeDto::code).doesNotContain("facade-glazing");
        assertThatThrownBy(() -> service.resolve("facade-glazing", null))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> service.resolve(null, custom.id()))
                .isInstanceOfAny(UnprocessableRequestException.class, ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(custom.id(), new UpdateTradeRequest("Stolen", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        disableOrgFilter();

        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
        assertThat(service.resolve(null, custom.id()).getCode()).isEqualTo("facade-glazing");
        disableOrgFilter();
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
