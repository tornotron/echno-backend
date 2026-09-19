package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalksEntryRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalksEntryDto;
import org.tornotron.echno_backend.modules.toolboxtalks.mapper.ToolboxTalksMapperImpl;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's table through the migration and the tenant boundary on every read: a row
 * written by one organization is absent for another, not forbidden.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ToolboxTalksService.class, ToolboxTalksMapperImpl.class, UserContextService.class, TenantEntityHelper.class})
class ToolboxTalksServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ToolboxTalksService service;

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
            orgAId = persistOrganization("Toolbox Talks Org A").getId();
            orgBId = persistOrganization("Toolbox Talks Org B").getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM toolbox_talks_entry WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void anEntryIsCreatedInTheCallersTenantAndReadBack() {
        ToolboxTalksEntryDto created = service.create(new CreateToolboxTalksEntryRequest("First", "notes"));

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("First");
        assertThat(service.get(created.id()).notes()).isEqualTo("notes");
        assertThat(service.list(0, 10).getContent()).extracting(ToolboxTalksEntryDto::title).containsExactly("First");
    }

    @Test
    void anotherTenantsEntryReadsAsAbsent() {
        UUID foreign = asTenant(orgBId, () -> service.create(new CreateToolboxTalksEntryRequest("Theirs", null)).id());

        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.list(0, 10).getContent()).isEmpty();
    }

    private <T> T asTenant(Long orgId, Supplier<T> work) {
        Long previous = TenantContext.getCurrentOrgId();
        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgId);
        enableOrgFilter(orgId);
        try {
            return work.get();
        } finally {
            disableOrgFilter();
            TenantContext.setCurrentOrgId(previous);
            enableOrgFilter(previous);
        }
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org;
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
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
}
