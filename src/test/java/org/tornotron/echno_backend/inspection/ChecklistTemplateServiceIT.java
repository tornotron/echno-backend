package org.tornotron.echno_backend.inspection;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.domain.InspectionCheckItem;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.inspection.dtos.StarterChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checklist library against a real CockroachDB: an org defines its own template
 * per trade, edits it, adopts a shipped starter as a copy, and cannot see another
 * org's. It also proves the Liquibase starter seed actually loaded, which no unit
 * test can.
 *
 * <p>The annotations and the {@code @Import} list repeat {@link InspectionServiceIT}
 * to the letter on purpose, so Spring's context cache serves all three inspection
 * test classes from one context. See that class for why that matters in a 1 GB test
 * JVM. Keep them in step when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class ChecklistTemplateServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ChecklistTemplateService service;

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
            Organization orgA = persistOrganization("Template Org A");
            Organization orgB = persistOrganization("Template Org B");
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
    }

    /**
     * Resets the per-test session state while the test transaction is still open.
     * The database rows are removed separately by {@link #removeCommittedRows()},
     * once that transaction has gone.
     */
    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    /**
     * Removes the rows the seed committed, after the test transaction has rolled
     * back rather than while it is still open.
     *
     * <p>{@code @AfterEach} runs inside the test transaction, so a delete issued
     * from there runs in a second, committed transaction while the first still
     * holds write intents on the same tables. CockroachDB may resolve that by
     * aborting the writer, or it may make the deleter wait for a transaction that
     * cannot commit until the delete returns. When it waits, the statement timeout
     * fires 30 seconds later and the test fails on cleanup with a query timeout and
     * no failed assertion, which is exactly the kind of failure that gets blamed on
     * an unrelated test. {@code @AfterTransaction} runs after the rollback, so
     * there are no intents left to contend with.
     */
    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM checklist_template_items WHERE template_id IN "
                    + "(SELECT id FROM checklist_templates WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM checklist_templates WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void create_storesTheCheckPointsInTheSubmittedOrderAtVersionOne() {
        ChecklistTemplateDto created = service.create(request(
                new ChecklistTemplateItemRequest("Cover", "Clear cover to the outermost bar",
                        "IS 456:2000 cl. 26.4", "40 mm", "Measured at five points", "+/- 5 mm",
                        true, "high"),
                new ChecklistTemplateItemRequest("Spacing", "Main bar spacing",
                        "IS 456:2000 cl. 26.3", "150 mm", "Measured at three locations", "+/- 10 mm",
                        true, null)));

        assertThat(created.trade()).isEqualTo(InspectionTrade.REINFORCEMENT);
        assertThat(created.version()).isEqualTo(1);
        // active is omitted on the request and defaults to true, so the template is
        // instantiated into new inspections straight away
        assertThat(created.active()).isTrue();
        assertThat(created.items()).extracting(item -> item.checkPoint())
                .containsExactly("Clear cover to the outermost bar", "Main bar spacing");
        assertThat(created.items().getFirst().lineOrder()).isZero();
        assertThat(created.items().get(1).lineOrder()).isEqualTo(1);
        assertThat(created.items().getFirst().tolerance()).isEqualTo("+/- 5 mm");
        // priority omitted, so it takes the same default the inspection check item uses
        assertThat(created.items().get(1).priority()).isEqualTo("medium");
    }

    @Test
    void create_refusesASecondTemplateForTheSameTrade() {
        service.create(request(anItem()));

        assertThatThrownBy(() -> service.create(request(anItem())))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("reinforcement");
    }

    @Test
    void update_replacesTheCheckPointsAndBumpsTheVersion() {
        UUID id = service.create(request(anItem())).id();

        ChecklistTemplateDto updated = service.update(id, new ChecklistTemplateRequest(
                InspectionTrade.REINFORCEMENT,
                "Reinforcement checklist, revision 2",
                "Tightened after the audit",
                false,
                List.of(new ChecklistTemplateItemRequest("Laps", "Lap length and stagger",
                        "IS 456:2000 cl. 26.2.5", "50d", "Measured on each lapped bar", "+/- 25 mm",
                        true, "high"))));

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.name()).isEqualTo("Reinforcement checklist, revision 2");
        assertThat(updated.active()).isFalse();
        assertThat(updated.items()).extracting(item -> item.checkPoint())
                .containsExactly("Lap length and stagger");
    }

    @Test
    void update_refusesToMoveATemplateToAnotherTrade() {
        UUID id = service.create(request(anItem())).id();

        assertThatThrownBy(() -> service.update(id, new ChecklistTemplateRequest(
                InspectionTrade.MASONRY, "Moved", null, null, List.of(anItem()))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be moved");
    }

    @Test
    void findStarters_returnsTheShippedSeedForEveryTrade() {
        List<StarterChecklistTemplateDto> starters = service.findStarters();

        assertThat(starters).hasSize(InspectionTrade.values().length);
        assertThat(starters).extracting(StarterChecklistTemplateDto::trade)
                .containsExactlyInAnyOrder(InspectionTrade.values());
        assertThat(starters).allSatisfy(starter -> assertThat(starter.items()).isNotEmpty());
    }

    @Test
    void adoptStarter_copiesTheShippedChecklistIntoTheTenant() {
        ChecklistTemplateDto adopted = service.adoptStarter(InspectionTrade.WATERPROOFING);

        StarterChecklistTemplateDto starter = service.findStarters().stream()
                .filter(candidate -> candidate.trade() == InspectionTrade.WATERPROOFING)
                .findFirst()
                .orElseThrow();

        assertThat(adopted.trade()).isEqualTo(InspectionTrade.WATERPROOFING);
        assertThat(adopted.version()).isEqualTo(1);
        assertThat(adopted.active()).isTrue();
        assertThat(adopted.items()).hasSameSizeAs(starter.items());
        assertThat(adopted.items()).extracting(item -> item.checkPoint())
                .isEqualTo(starter.items().stream().map(item -> item.checkPoint()).toList());
        // the copy is independent: it has its own row ids, not the starter's
        assertThat(adopted.items().getFirst().id())
                .isNotEqualTo(starter.items().getFirst().id());
    }

    @Test
    void adoptStarter_refusesWhenTheTenantAlreadyHasATemplateForTheTrade() {
        service.create(request(anItem()));

        assertThatThrownBy(() -> service.adoptStarter(InspectionTrade.REINFORCEMENT))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void findById_isScopedToTheOwningTenant() {
        UUID id = service.create(request(anItem())).id();
        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 10);

        enableOrgFilter(orgBId);
        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.findAll(null, null, pageable).getTotalElements()).isZero();
        // and an inspection in the other tenant gets no check points from it
        assertThat(service.instantiateFor(InspectionTrade.REINFORCEMENT)).isEmpty();
        disableOrgFilter();

        enableOrgFilter(orgAId);
        assertThat(service.findById(id).id()).isEqualTo(id);
        assertThat(service.findAll(InspectionTrade.REINFORCEMENT, true, pageable).getTotalElements())
                .isEqualTo(1);
        assertThat(service.findAll(InspectionTrade.MASONRY, null, pageable).getTotalElements())
                .isZero();
        disableOrgFilter();
    }

    @Test
    void instantiateFor_skipsARetiredTemplateAndAnUnknownTrade() {
        UUID id = service.create(request(anItem())).id();
        assertThat(service.instantiateFor(InspectionTrade.REINFORCEMENT)).hasSize(1);

        // no template at all for this trade
        assertThat(service.instantiateFor(InspectionTrade.FLOORING)).isEmpty();
        // and no trade at all, which is every safety and compliance inspection
        assertThat(service.instantiateFor(null)).isEmpty();

        service.update(id, new ChecklistTemplateRequest(InspectionTrade.REINFORCEMENT,
                "Retired", null, false, List.of(anItem())));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.instantiateFor(InspectionTrade.REINFORCEMENT)).isEmpty();
    }

    @Test
    void instantiateFor_copiesTheCriteriaOntoUnansweredCheckItems() {
        service.create(request(new ChecklistTemplateItemRequest("Cover",
                "Clear cover to the outermost bar", "IS 456:2000 cl. 26.4", "40 mm",
                "Measured at five points", "+/- 5 mm", true, "high")));

        List<InspectionCheckItem> items = service.instantiateFor(InspectionTrade.REINFORCEMENT);

        assertThat(items).hasSize(1);
        InspectionCheckItem item = items.getFirst();
        assertThat(item.getCheckPoint()).isEqualTo("Clear cover to the outermost bar");
        assertThat(item.getAcceptanceCriterion()).isEqualTo("Measured at five points");
        assertThat(item.getTolerance()).isEqualTo("+/- 5 mm");
        assertThat(item.getExpectedValue()).isEqualTo("40 mm");
        assertThat(item.getPriority()).isEqualTo("high");
        assertThat(item.isPhotosRequired()).isTrue();
        assertThat(item.getStatus()).isEqualTo(CheckItemStatus.PENDING);
        assertThat(item.getMeasurement()).isNull();
        // not attached to an inspection yet: the caller does that
        assertThat(item.getInspection()).isNull();
    }

    private static ChecklistTemplateRequest request(ChecklistTemplateItemRequest... items) {
        return new ChecklistTemplateRequest(InspectionTrade.REINFORCEMENT,
                "Reinforcement checklist", "Pre-pour check", null, List.of(items));
    }

    private static ChecklistTemplateItemRequest anItem() {
        return new ChecklistTemplateItemRequest("Spacing", "Main bar spacing",
                null, "150 mm", null, "+/- 10 mm", false, null);
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
