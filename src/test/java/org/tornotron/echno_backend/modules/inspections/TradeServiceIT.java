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

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    private static final Set<String> ORIGINAL_TRADES = Set.of(
            "pre-construction-documentation", "shuttering-formwork", "reinforcement", "rcc", "masonry",
            "plastering", "waterproofing", "flooring", "fabrication", "aluminium-upvc", "electrical-fixtures",
            "plumbing-fixtures", "sanitary-fixtures", "finishing", "dimensional-check", "progress-check");
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
    void catalogue_holdsTheSixteenOriginalSlugsAndTheFiveNewTradesInSevenGroups() {
        List<TradeCatalogueDto> catalogue = service.listCatalogue();

        Set<String> codes = catalogue.stream().map(TradeCatalogueDto::code).collect(Collectors.toSet());
        assertThat(codes).containsAll(ORIGINAL_TRADES);
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentFirstReads_bothSucceedAndTheCatalogueIsCopiedOnce() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<OrgTrade> before = txTemplate.execute(s -> orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgAId));
        assertThat(before).isEmpty();
        CyclicBarrier bothInside = new CyclicBarrier(2);
        Callable<Integer> firstRead = () -> {
            TenantContext.setCurrentOrgId(orgAId);
            try {
                return txTemplate.execute(status -> {
                    Organization org = entityManager.find(Organization.class, orgAId);
                    try {
                        bothInside.await(20, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return service.ensureOrgTrades(org);
                });
            } finally {
                TenantContext.clear();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int copied = 0;
        int serialized = 0;
        try {
            List<Future<Integer>> reads = List.of(executor.submit(firstRead), executor.submit(firstRead));
            for (Future<Integer> read : reads) {
                try {
                    copied += read.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // Under CockroachDB's serializable isolation the second copy may still be told to
                    // restart its transaction; that surfaces as the 409 "retry" the API already maps.
                    // What must never happen again is the unique-key refusal that was a 500.
                    assertThat(e.getCause()).isInstanceOf(CannotAcquireLockException.class);
                    serialized++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(copied).as("copied by the reads that committed").isEqualTo(21);
        assertThat(serialized).isLessThanOrEqualTo(1);
        List<OrgTrade> after = txTemplate.execute(s -> orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgAId));
        assertThat(after).hasSize(21);
        // and the read that was told to retry finds the copy done
        Integer topUp = txTemplate.execute(s -> service.ensureOrgTrades(entityManager.find(Organization.class, orgAId)));
        assertThat(topUp).isZero();
    }

    @Test
    void everyOriginalSlug_resolvesToTheOrgRowBySlugAndById() {
        for (String code : ORIGINAL_TRADES) {
            OrgTrade row = service.resolve(code, null);
            assertThat(row.getCode()).isEqualTo(code);
            assertThat(row.getCatalogueCode()).isEqualTo(code);
            // resolving by id lands on the same row
            assertThat(service.resolve(null, row.getId()).getId()).isEqualTo(row.getId());
        }
    }

    @Test
    void theFiveNewTrades_resolveLikeAnyOtherCatalogueCode() {
        for (String code : NEW_TRADES) {
            OrgTrade row = service.resolve(code, null);
            assertThat(row.getCode()).isEqualTo(code);
            assertThat(row.getCatalogueCode()).isEqualTo(code);
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
