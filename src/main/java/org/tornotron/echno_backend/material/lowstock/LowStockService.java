package org.tornotron.echno_backend.material.lowstock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.material.dto.LowStockMaterialDto;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationScope;

/**
 * Answers which materials have reached their reorder level.
 *
 * <p>Setting a reorder level is an act somebody performs expecting a consequence, and until this
 * existed the only consequence anywhere was a badge the web app computed for itself over whatever
 * materials that browser had loaded. That comparison is not wrong so much as partial: it is made
 * over one uncapped list endpoint's worth of catalogue, it is made against the material's global
 * level even on the screen that breaks stock down by storage location, and it exists only while
 * somebody has the screen open. This is the same comparison made where the data is, over all of it,
 * at the scope the caller asks about.
 *
 * <h2>Scope</h2>
 *
 * <p>Three, matching the scopes the material stock endpoint already reads at, because the answer is
 * genuinely different at each and picking one would be picking wrong for somebody. A material can
 * be comfortable across an organization and nearly out at four of its sites, and on this
 * installation one is: the aggregate figure the dashboard shows says it is fine.
 *
 * <h2>Nothing is scheduled, and nothing is raised</h2>
 *
 * <p>This is a query. It costs nothing when nobody asks, and it tells nobody anything at two in the
 * morning. Making it tell somebody needs a delivery path, and the one this installation has does
 * not work: see the issue this was built for.
 */
@Service
public class LowStockService {

    private final LowStockRepository lowStockRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;

    public LowStockService(LowStockRepository lowStockRepository,
                           ProjectRepository projectRepository,
                           StorageLocationRepository storageLocationRepository) {
        this.lowStockRepository = lowStockRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
    }

    /**
     * The materials at or below their reorder level, at the scope the arguments describe.
     *
     * <p>Both ids absent reads the whole organization; a project alone totals that project's
     * locations; a project and a location read that one location, and only there is a per-location
     * threshold override applied.
     *
     * <p>A project or location that does not exist in this tenant is a 404 rather than an empty
     * page. On this endpoint the difference matters more than it usually does: an empty page means
     * "nothing has run out", which is the most reassuring answer the endpoint can give, and it must
     * never be the answer to a question that was never actually asked of any real project.
     *
     * @param projectId The project to scope to, or null for the whole organization.
     * @param storageLocationId The storage location to scope to. Requires a project, because stock
     *         is held per project and location together.
     * @param pageable Which page to return. Any sort on it is dropped: the query orders by severity.
     * @return A page of materials at or below their level, most depleted first.
     * @throws InvalidRequestException if a storage location is given without a project, or if it
     *         belongs to a different project.
     * @throws ResourceNotFoundException if the project or storage location is not in this tenant.
     */
    @Transactional(readOnly = true)
    public Page<LowStockMaterialDto> findLowStock(Long projectId, Long storageLocationId,
                                                  Pageable pageable) {
        Long orgId = TenantContext.getCurrentOrgId();
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        if (storageLocationId != null && projectId == null) {
            throw new InvalidRequestException(
                    "A storage location can only be read within a project, so projectId is required "
                            + "when storageLocationId is given");
        }

        if (projectId != null && !projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException(
                    "Project with ID " + projectId + " was not found in this organization");
        }
        if (storageLocationId != null) {
            StorageLocation location = storageLocationRepository
                    .findByIdAndOrganization_Id(storageLocationId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID "
                            + storageLocationId + " was not found in this organization"));
            // A location belonging to another project can hold no stock for this one, so the
            // query over that pairing would return nothing and read as "nothing has run out".
            // The same rule every write path applies, applied here for that reason.
            StorageLocationScope.requireUsableFromProject(location, projectId);
        }

        Page<LowStockRow> rows;
        if (storageLocationId != null) {
            rows = lowStockRepository.findLowStockAtStorageLocation(orgId, projectId, storageLocationId, unsorted);
        } else if (projectId != null) {
            rows = lowStockRepository.findLowStockForProject(orgId, projectId, unsorted);
        } else {
            rows = lowStockRepository.findLowStockForOrganization(orgId, unsorted);
        }

        return rows.map(row -> toDto(row, projectId, storageLocationId));
    }

    /**
     * One row as the API returns it.
     *
     * <p>The shortfall is computed here rather than in the query because it is arithmetic on two
     * numbers the row already carries, and it is floored at zero so a material sitting exactly on
     * its level reports nothing missing rather than a negative quantity. The scope ids are echoed
     * from the request rather than joined for, since the caller named them and a join to fetch back
     * what was passed in would be two more tables for no new information.
     */
    private LowStockMaterialDto toDto(LowStockRow row, Long projectId, Long storageLocationId) {
        LowStockMaterialDto dto = new LowStockMaterialDto();
        dto.setMaterialId(row.materialId());
        dto.setSku(row.sku());
        dto.setMaterialName(row.materialName());
        dto.setUnit(row.unit());
        dto.setCurrentStock(row.currentStock());
        dto.setReorderLevel(row.reorderLevel());
        dto.setMoq(row.moq());
        dto.setProjectId(projectId);
        dto.setStorageLocationId(storageLocationId);

        double shortfall = row.reorderLevel() - row.currentStock();
        dto.setShortfall(Math.max(0.0, shortfall));
        return dto;
    }
}
