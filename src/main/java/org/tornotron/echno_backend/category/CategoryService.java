package org.tornotron.echno_backend.category;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.category.mapper.CategoryMapper;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.category.dto.CategorySimpleDto;
import org.tornotron.echno_backend.category.dto.WorkSubcategoryDto;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectAccess;

import java.util.List;


/**
 * Service class for managing categories.
 * Handles business logic related to category creation, retrieval, and deletion.
 *
 * <p>Work categories sit in the projects area of the access matrix. A category belongs to its
 * organization and to no project, so every category has the same facts and a caller reaches either
 * all of them or none: only a grant covering every record of the organization reaches one.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final CategoryMapper categoryMapper;
    private final WorkSubcategoryRepository workSubcategoryRepository;
    private final ProjectAccess projectAccess;

    /**
     * Constructs a CategoryService with the given CategoryRepository.
     *
     * @param categoryRepository The repository for category data access.
     * @param tenantEntityHelper The helper for resolving the current organization.
     * @param projectAccess      Says whether the caller may read and change the organization's categories.
     */
    public CategoryService(CategoryRepository categoryRepository, TenantEntityHelper tenantEntityHelper,
                           CategoryMapper categoryMapper, WorkSubcategoryRepository workSubcategoryRepository,
                           ProjectAccess projectAccess) {
        this.categoryRepository = categoryRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.categoryMapper = categoryMapper;
        this.workSubcategoryRepository = workSubcategoryRepository;
        this.projectAccess = projectAccess;
    }

    /**
     * Creates a new category.
     *
     * @param categoryCreationDto DTO containing the details for the new category.
     * @throws DatabaseOperationException if the category cannot be saved.
     */
    @Transactional
    public CategorySimpleDto addCategory(CategoryCreationDto categoryCreationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        projectAccess.require(AccessPermission.CREATE, ProjectAccess.organizationLevel(organization.getId()));
        String normalized = CategoryNormalizer.normalize(categoryCreationDto.getName());
        if(categoryRepository.existsByNormalizedNameAndOrganization_Id(normalized, organization.getId())) {
            throw new IllegalArgumentException("Category with name " + normalized + " already exists.");
        }
        Category category = new Category();
        category.setOrganization(organization);
        category.setName(categoryCreationDto.getName());
        category.setNormalizedName(normalized);
        category.setDescription(categoryCreationDto.getDescription());
        category.setIcon(categoryCreationDto.getIcon());
        category.setImage(categoryCreationDto.getImage());
        return categoryMapper.toSimpleDto(categoryRepository.save(category));
    }

    /**
     * Retrieves a paginated list of all categories.
     *
     * @param pageNo   The page number to retrieve.
     * @param pageSize The number of categories per page.
     * @return A {@link Page} of category DTOs.
     */
    @Transactional(readOnly = true)
    public Page<CategoryDto> getAllCategories(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        // Every category has the same facts, so the caller's filter admits all of them or none, and
        // asking about one category answers for the list.
        if (!projectAccess.allows(AccessPermission.READ, ProjectAccess.organizationLevel(TenantContext.getCurrentOrgId()))) {
            return Page.empty(pageable);
        }
        return categoryRepository.findAll(pageable)
                .map(categoryMapper::toDto);
    }

    /**
     * Retrieves a single category by its ID.
     *
     * @param id The ID of the category to retrieve.
     * @return The category DTO.
     * @throws ResourceNotFoundException if no category with the given ID is found.
     */
    @Transactional(readOnly = true)
    public CategoryDto getACategory(Long id) {
        Category category = categoryRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> categoryNotFound(id));
        requireCategory(id, AccessPermission.READ);
        return categoryMapper.toDto(category);
    }

    /**
     * Deletes a category by its ID.
     *
     * @param id The ID of the category to delete.
     * @throws ResourceNotFoundException if no category with the given ID is found.
     */
    @Transactional
    public void deleteACategory(Long id) {
        if(!categoryRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw categoryNotFound(id);
        }
        requireCategory(id, AccessPermission.DELETE);
        categoryRepository.deleteById(id);
    }

    /**
     * The sub-categories of one of this organization's work categories, in dropdown order.
     *
     * @param categoryId The work category.
     * @return Its sub-categories, empty when it has none.
     * @throws ResourceNotFoundException if no such category exists in this organization.
     */
    @Transactional(readOnly = true)
    public List<WorkSubcategoryDto> getSubcategories(Long categoryId) {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (!categoryRepository.existsByIdAndOrganization_Id(categoryId, organizationId)) {
            throw categoryNotFound(categoryId);
        }
        requireCategory(categoryId, AccessPermission.READ);
        return workSubcategoryRepository
                .findByCategory_IdAndOrganization_IdOrderBySortOrderAscIdAsc(categoryId, organizationId)
                .stream()
                .map(WorkSubcategoryDto::from)
                .toList();
    }

    /**
     * Refuses unless the caller may act this way on one of the organization's categories, which exists:
     * one they may not read reads as missing, and one they may read but not act on is refused.
     */
    private void requireCategory(Long id, String action) {
        RecordFacts facts = ProjectAccess.organizationLevel(TenantContext.getCurrentOrgId());
        projectAccess.requireOn(action, facts, () -> categoryNotFound(id));
    }

    private static ResourceNotFoundException categoryNotFound(Long id) {
        return new ResourceNotFoundException("Category not found with id: " + id);
    }
}
