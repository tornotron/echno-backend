package org.tornotron.echno_backend.modules.assistant.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantDocumentUnit;
import org.tornotron.echno_backend.modules.assistant.domain.VectorText;

@Repository
public interface AssistantDocumentUnitRepository extends JpaRepository<AssistantDocumentUnit, UUID> {

    /**
     * The chunks nearest to a query vector, within one organization's named projects.
     *
     * <p>Native SQL, because JPQL has no vector distance. Hibernate's {@code orgFilter} does not
     * apply to native SQL, so the organization and the projects are in the statement itself:
     * they are the prefix of the vector index, so the search only ever visits that tenant's and
     * those projects' vectors, and the rows it returns are the count within the tenant. Never
     * filter the result afterwards; that reads other tenants' rows and leaks counts.
     *
     * <p>A row with a null {@code project_id} (an organization-wide document) never matches the
     * {@code IN} list, so it is not returned.
     */
    @Query(value = """
            SELECT id AS "id", attachment_id AS "attachmentId", page_no AS "pageNo",
                   char_start AS "charStart", char_end AS "charEnd", content AS "content",
                   embedding <-> CAST(:q AS VECTOR) AS "distance"
            FROM assistant_document_unit
            WHERE organization_id = :orgId AND project_id IN (:projectIds)
            ORDER BY embedding <-> CAST(:q AS VECTOR)
            LIMIT :k
            """, nativeQuery = true)
    List<NearestChunk> nearestNative(@Param("q") String queryVector,
                                     @Param("orgId") Long organizationId,
                                     @Param("projectIds") Collection<Long> projectIds,
                                     @Param("k") int k);

    /**
     * {@link #nearestNative} with the two ways it could be asked to run unscoped refused first.
     * No organization is an error, never an unfiltered search; no projects is an empty answer,
     * never "all projects".
     */
    default List<NearestChunk> nearest(float[] queryVector, Long organizationId,
                                       Collection<Long> projectIds, int k) {
        if (organizationId == null) {
            throw new TenantIdMissingException(
                    "A similarity search needs an explicit organization id; refusing to run unscoped");
        }
        if (projectIds == null || projectIds.isEmpty() || k <= 0) {
            return List.of();
        }
        return nearestNative(VectorText.of(queryVector), organizationId, projectIds, k);
    }

    /** One result row of the similarity query. */
    interface NearestChunk {
        UUID getId();

        Long getAttachmentId();

        Integer getPageNo();

        Integer getCharStart();

        Integer getCharEnd();

        String getContent();

        Double getDistance();
    }
}
