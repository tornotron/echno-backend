package org.tornotron.echno_backend.material.summary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.material.dto.MaterialStockSummaryDto;
import org.tornotron.echno_backend.project.ProjectRepository;

/**
 * The materials dashboard strip, totalled where the data is.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Three tiles on the materials console are aggregates over the whole catalogue: the value of
 * the stock on hand, how many materials there are, and how many units of measure they are held in.
 * All three were computed in the browser over the array one capped listing returned, which is at
 * most 500 rows. Past that the tiles described 500 materials and were captioned as describing the
 * inventory. The money one was the dangerous one, because a caption does not travel with a figure
 * once somebody repeats it: a catalogue of 743 materials rendered a confident five crore that was
 * two thirds of the truth.
 *
 * <p>The count could have been fixed in the browser and was, from a page's {@code totalElements}.
 * The sum could not: totalling 743 rows means holding 743 rows, and not holding them is what the
 * cap is for. So the console stopped showing the figure. This is what lets it show one again.
 *
 * <h2>Scope</h2>
 *
 * <p>Two, matching the first two the low-stock read offers, because a project's material view
 * showing an organization-wide total would be the same class of misleading figure this replaced.
 * The scopes differ in what they count as a material, and deliberately:
 *
 * <ul>
 *   <li><b>Organization.</b> The catalogue. Every material is counted whether or not it has ever
 *       been stocked, because that is what the catalogue is.
 *   <li><b>Project.</b> What the project carries a balance row for. Counting the catalogue here
 *       would report every material against a project that has never held one, and the count would
 *       no longer describe the same rows the value was summed over.
 * </ul>
 *
 * <h2>Stock with no cost behind it</h2>
 *
 * <p>A receipt posted with no unit cost adds quantity at no value, so its balance row holds stock
 * worth zero. The sum counts that row at the zero it holds, which is the only figure anybody has
 * for it, and reports how many such rows it counted. Excluding them would understate silently;
 * refusing the whole total over one of them would throw away a figure that is right about
 * everything else. Naming them lets the console decide, which is the same judgement the tile
 * already makes about the cap.
 */
@Service
public class MaterialStockSummaryService {

    private final MaterialRepository materialRepository;
    private final CurrentStockRepository currentStockRepository;
    private final ProjectRepository projectRepository;

    public MaterialStockSummaryService(MaterialRepository materialRepository,
                                       CurrentStockRepository currentStockRepository,
                                       ProjectRepository projectRepository) {
        this.materialRepository = materialRepository;
        this.currentStockRepository = currentStockRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * The catalogue and stock-value figures for the current tenant, or for one of its projects.
     *
     * <p>A project that does not exist in this tenant is a 404 rather than a summary of zeroes,
     * for the reason the low-stock read gives: zeroes are an answer, and they must not be the
     * answer to a question that was never asked of any real project.
     *
     * @param projectId The project to total within, or null for the whole organization.
     * @return The four figures, all at the same scope.
     * @throws ResourceNotFoundException if the project is not in this tenant.
     */
    @Transactional(readOnly = true)
    public MaterialStockSummaryDto summarize(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (projectId == null) {
            return new MaterialStockSummaryDto(
                    null,
                    materialRepository.countForOrganization(orgId),
                    materialRepository.countDistinctUnitsForOrganization(orgId),
                    currentStockRepository.sumStockValueForOrganization(orgId),
                    currentStockRepository.countUnvaluedHoldingsForOrganization(orgId));
        }

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException(
                    "Project with ID " + projectId + " was not found in this organization");
        }

        return new MaterialStockSummaryDto(
                projectId,
                currentStockRepository.countDistinctMaterialsForProject(orgId, projectId),
                currentStockRepository.countDistinctUnitsForProject(orgId, projectId),
                currentStockRepository.sumStockValueForProject(orgId, projectId),
                currentStockRepository.countUnvaluedHoldingsForProject(orgId, projectId));
    }
}
