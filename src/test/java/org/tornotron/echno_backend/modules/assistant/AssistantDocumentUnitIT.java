package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantDocumentUnit;
import org.tornotron.echno_backend.modules.assistant.domain.VectorText;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository.NearestChunk;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The spike the whole assistant design rests on: that CockroachDB's {@code VECTOR} column works
 * from JPA, and that a similarity search cannot reach another organization's rows. Runs on the
 * real migration, on the CockroachDB container the rest of the suite uses.
 *
 * <p>Two organizations hold chunks with the same vector, so the nearest row to the query is
 * exactly equal in both. Whatever keeps them apart is therefore the statement's own scope and the
 * index prefix, not a difference in the data.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssistantDocumentUnitIT extends AbstractIntegrationTest {

    @Autowired
    private AssistantDocumentUnitRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectA1Id;
    private Long projectA2Id;
    private Long projectBId;
    private Long attachmentA1Id;
    private Long attachmentA2Id;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Assistant Org A");
            Organization orgB = persistOrganization("Assistant Org B");
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectA1Id = persistProject(orgA, "Assistant Tower A1").getId();
            projectA2Id = persistProject(orgA, "Assistant Tower A2").getId();
            projectBId = persistProject(orgB, "Assistant Tower B").getId();
            attachmentA1Id = persistAttachment(orgA).getId();
            attachmentA2Id = persistAttachment(orgA).getId();
            Long attachmentBId = persistAttachment(orgB).getId();

            persistChunk(orgA, projectA1Id, attachmentA1Id, 0, "a1 near", vec(0, 1.0f));
            persistChunk(orgA, projectA1Id, attachmentA1Id, 1, "a1 far", vec(1, 1.0f));
            persistChunk(orgA, projectA2Id, attachmentA2Id, 0, "a2 close", vec(0, 0.9f));
            persistChunk(orgA, null, attachmentA2Id, 1, "a organisation-wide", vec(0, 1.0f));
            // Exactly the vector of "a1 near", in the other organization.
            persistChunk(orgB, projectBId, attachmentBId, 0, "b exact", vec(0, 1.0f));
        });
    }

    @AfterEach
    void removeCommittedRows() {
        disableOrgFilter();
        TenantContext.clear();
        if (orgAId == null) {
            return;
        }
        inCommittedTx(() -> {
            // The attachment rows go first: their chunks cascade, which is itself under test.
            deleteForOrgs("DELETE FROM assistant_document_unit WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM attachment WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void theVectorColumnMapsThroughTheEntityOnTheDialectTheApplicationUses() {
        // Path 1 (hibernate-vector, @JdbcTypeCode(SqlTypes.VECTOR)) works because the dialect
        // Hibernate resolves for this datasource is PostgreSQLDialect, not CockroachDialect. Under
        // CockroachDialect the vector type is never registered and every insert fails with
        // "invalid cast: bytes -> vector". If a dialect is ever pinned, this fails first, and the
        // fallback is the spec's second path: map the column insertable = false and write it with
        // a native UPDATE ... SET embedding = CAST(:v AS VECTOR).
        String dialect = entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
                .getJdbcServices().getDialect().getClass().getSimpleName();
        assertThat(dialect)
                .as("the vector mapping depends on the dialect the application resolves")
                .isEqualTo("PostgreSQLDialect");

        float[] vector = vec(3, 0.25f);
        vector[10] = 1.0E-5f;
        AssistantDocumentUnit saved = inCommittedTxReturning(() ->
                repository.saveAndFlush(chunk(entityManager.getReference(Organization.class, orgAId),
                        projectA1Id, attachmentA1Id, 9, "roundtrip", vector)));
        entityManager.clear();

        float[] read = repository.findById(saved.getId()).orElseThrow().getEmbedding();

        assertThat(read).hasSize(AssistantDocumentUnit.EMBEDDING_DIMENSION);
        assertThat(read[3]).isEqualTo(0.25f);
        assertThat(read[10]).isEqualTo(1.0E-5f);
    }

    @Test
    void theClusterSettingAndTheMigrationGiveTheVectorIndexItsPrefixColumns() {
        Object setting = entityManager.createNativeQuery("SHOW CLUSTER SETTING feature.vector_index.enabled")
                .getSingleResult();
        assertThat(setting).as("feature.vector_index.enabled on the test image").isIn(true, "true", "t");

        String ddl = (String) ((Object[]) entityManager
                .createNativeQuery("SHOW CREATE TABLE assistant_document_unit").getSingleResult())[1];
        assertThat(ddl).contains("VECTOR INDEX assistant_document_unit_embedding_idx "
                + "(organization_id, project_id, embedding vector_l2_ops)");
        assertThat(ddl).contains("embedding VECTOR(1024) NOT NULL")
                .contains("REFERENCES public.attachment(id) ON DELETE CASCADE");
    }

    @Test
    void nearestReturnsOnlyTheAskingOrganizationsRowsInDistanceOrder() {
        List<NearestChunk> rows = repository.nearest(vec(0, 1.0f), orgAId, List.of(projectA1Id, projectA2Id), 10);

        // "a2 close" (0.9) sits between the exact match and the orthogonal row; "b exact" is the
        // same vector as "a1 near" but belongs to the other organization.
        assertThat(rows).extracting(NearestChunk::getContent).containsExactly("a1 near", "a2 close", "a1 far");
        assertThat(rows).extracting(NearestChunk::getDistance).isSorted();
        assertThat(rows.get(0).getDistance()).isLessThan(1.0E-6);
        assertThat(rows.get(0).getAttachmentId()).isEqualTo(attachmentA1Id);
    }

    @Test
    void theOtherOrganizationsSameVectorIsReturnedToItAlone() {
        List<NearestChunk> rows = repository.nearest(vec(0, 1.0f), orgBId, List.of(projectBId), 10);

        assertThat(rows).extracting(NearestChunk::getContent).containsExactly("b exact");
    }

    @Test
    void aProjectOfAnotherOrganizationNamedWithMyOrganizationIdFindsNothing() {
        assertThat(repository.nearest(vec(0, 1.0f), orgAId, List.of(projectBId), 10)).isEmpty();
        assertThat(repository.nearest(vec(0, 1.0f), orgBId, List.of(projectA1Id), 10)).isEmpty();
    }

    @Test
    void theCountReturnedIsTheCountWithinTheTenantNotTheTable() {
        // Far more rows asked for than exist: only the tenant's own reachable rows come back.
        assertThat(repository.nearest(vec(0, 1.0f), orgAId, List.of(projectA1Id, projectA2Id), 1000))
                .hasSize(3);
        assertThat(repository.nearest(vec(0, 1.0f), orgBId, List.of(projectBId), 1000)).hasSize(1);
    }

    @Test
    void anOrganizationWideChunkIsNotReachableThroughAProjectScope() {
        List<String> contents = repository.nearest(vec(0, 1.0f), orgAId, List.of(projectA1Id, projectA2Id), 10)
                .stream().map(NearestChunk::getContent).toList();

        assertThat(contents).doesNotContain("a organisation-wide");
    }

    @Test
    void aSearchWithNoOrganizationIsRefusedRatherThanRunUnscoped() {
        assertThatThrownBy(() -> repository.nearest(vec(0, 1.0f), null, List.of(projectA1Id), 10))
                .isInstanceOf(TenantIdMissingException.class);
    }

    @Test
    void aSearchWithNoProjectsIsAnEmptyAnswerNeverAllProjects() {
        assertThat(repository.nearest(vec(0, 1.0f), orgAId, List.of(), 10)).isEmpty();
        assertThat(repository.nearest(vec(0, 1.0f), orgAId, (Collection<Long>) null, 10)).isEmpty();
    }

    @Test
    void theOrgFilterScopesEntityReadsOfTheSameTable() {
        enableOrgFilter(orgAId);
        Long countA = (Long) entityManager.createQuery("select count(u) from AssistantDocumentUnit u")
                .getSingleResult();
        disableOrgFilter();
        enableOrgFilter(orgBId);
        Long countB = (Long) entityManager.createQuery("select count(u) from AssistantDocumentUnit u")
                .getSingleResult();

        assertThat(countA).as("organization A's rows, including its organization-wide chunk").isEqualTo(4);
        assertThat(countB).isEqualTo(1);
    }

    @Test
    void theQueryPlanOnlyVisitsTheTenantsAndTheProjectsPrefixOfTheIndex() throws Exception {
        // The real statement, not a copy of it, with bound parameters as the application sends them.
        String sql = AssistantDocumentUnitRepository.class
                .getMethod("nearestNative", String.class, Long.class, Collection.class, int.class)
                .getAnnotation(Query.class).value();
        @SuppressWarnings("unchecked")
        List<Object> plan = entityManager.createNativeQuery("EXPLAIN " + sql)
                .setParameter("q", VectorText.of(vec(0, 1.0f)))
                .setParameter("orgId", orgAId)
                .setParameter("projectIds", List.of(projectA1Id, projectA2Id))
                .setParameter("k", 5)
                .getResultList();
        String text = String.join(" | ", plan.stream().map(Object::toString).toList());

        assertThat(text).contains("vector search").contains("assistant_document_unit_embedding_idx");
        assertThat(text).contains("prefix spans: [/" + orgAId + "/" + projectA1Id + " - /" + orgAId + "/"
                + projectA1Id + "] [/" + orgAId + "/" + projectA2Id + " - /" + orgAId + "/" + projectA2Id + "]");
        assertThat(text).doesNotContain("/" + orgBId + "/");
    }

    @Test
    void deletingTheAttachmentDeletesItsChunks() {
        Long before = countChunksOfAttachment(attachmentA1Id);
        assertThat(before).isEqualTo(2);

        inCommittedTx(() -> entityManager.createNativeQuery("DELETE FROM attachment WHERE id = :id")
                .setParameter("id", attachmentA1Id).executeUpdate());

        assertThat(countChunksOfAttachment(attachmentA1Id)).isZero();
        // The other attachment's chunks are untouched.
        assertThat(countChunksOfAttachment(attachmentA2Id)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    // Each count in a transaction of its own: the test's outer transaction has a fixed read
    // timestamp, so under CockroachDB's serializable isolation it would not see (or would be
    // forced to retry over) a delete that another transaction committed in the middle of the test.
    private Long countChunksOfAttachment(Long attachmentId) {
        return inCommittedTxReturning(() -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM assistant_document_unit WHERE attachment_id = :id")
                .setParameter("id", attachmentId).getSingleResult()).longValue());
    }

    private static float[] vec(int index, float value) {
        float[] vector = new float[AssistantDocumentUnit.EMBEDDING_DIMENSION];
        vector[index] = value;
        return vector;
    }

    private AssistantDocumentUnit chunk(Organization org, Long projectId, Long attachmentId, int index,
                                        String content, float[] embedding) {
        AssistantDocumentUnit unit = new AssistantDocumentUnit();
        unit.setOrganization(org);
        unit.setProjectId(projectId);
        unit.setAttachmentId(attachmentId);
        unit.setChunkIndex(index);
        unit.setContent(content);
        unit.setContentHash("hash-" + content);
        unit.setEmbedding(embedding);
        unit.setEmbeddingModel("it");
        return unit;
    }

    private void persistChunk(Organization org, Long projectId, Long attachmentId, int index, String content,
                              float[] embedding) {
        entityManager.persist(chunk(org, projectId, attachmentId, index, content, embedding));
        entityManager.flush();
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

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        return project;
    }

    private Attachment persistAttachment(Organization org) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("ASSISTANT_IT");
        attachment.setEntityUuid(UUID.randomUUID());
        attachment.setStorageKey("assistant-it/" + UUID.randomUUID());
        attachment.setCreatedAt(LocalDateTime.now());
        attachment.setOrganization(org);
        entityManager.persist(attachment);
        entityManager.flush();
        return attachment;
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

    private <T> T inCommittedTxReturning(java.util.function.Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> work.get());
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }
}
