package org.tornotron.echno_backend.project.spatial;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every lookup is a query rather than a primary-key {@code find()}, so the Hibernate
 * {@code orgFilter} applies and a node of another tenant reads as absent.
 */
@Repository
public interface SpatialNodeRepository extends JpaRepository<SpatialNode, UUID> {

    @Query("SELECT n FROM SpatialNode n WHERE n.id = :id AND n.projectId = :projectId")
    Optional<SpatialNode> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") Long projectId);

    @Query("SELECT n FROM SpatialNode n WHERE n.id = :id")
    Optional<SpatialNode> findByIdScoped(@Param("id") UUID id);

    List<SpatialNode> findByProjectIdOrderByDepthAscSortOrderAscCodeAsc(Long projectId);

    List<SpatialNode> findByProjectIdAndArchivedAtIsNullOrderByDepthAscSortOrderAscCodeAsc(Long projectId);

    /** The node itself and every descendant, the path prefix being the node's own path. */
    List<SpatialNode> findByProjectIdAndPathStartingWith(Long projectId, String pathPrefix);

    List<SpatialNode> findByProjectIdAndParentIdAndArchivedAtIsNull(Long projectId, UUID parentId);

    boolean existsByProjectIdAndParentIdAndCode(Long projectId, UUID parentId, String code);

    boolean existsByProjectIdAndParentIdIsNullAndCode(Long projectId, String code);

    Optional<SpatialNode> findByProjectIdAndParentIdAndCode(Long projectId, UUID parentId, String code);

    Optional<SpatialNode> findByProjectIdAndParentIdIsNullAndCode(Long projectId, String code);

    boolean existsByProjectIdAndBimElementGuidAndIdNot(Long projectId, String bimElementGuid, UUID id);

    Optional<SpatialNode> findByProjectIdAndBimElementGuid(Long projectId, String bimElementGuid);

    @Query("SELECT n FROM SpatialNode n WHERE n.id IN :ids")
    List<SpatialNode> findAllScoped(@Param("ids") List<UUID> ids);
}
