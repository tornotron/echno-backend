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
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;


/**
 * Service class for managing categories.
 * Handles business logic related to category creation, retrieval, and deletion.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final CategoryMapper categoryMapper;

    /**
     * Constructs a CategoryService with the given CategoryRepository.
     *
     * @param categoryRepository The repository for category data access.
     * @param tenantEntityHelper The helper for resolving the current organization.
     */
    public CategoryService(CategoryRepository categoryRepository, TenantEntityHelper tenantEntityHelper, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * Creates a new category.
     *
     * @param categoryCreationDto DTO containing the details for the new category.
     * @throws DatabaseOperationException if the category cannot be saved.
     */
    @Transactional
    public CategorySimpleDto addCategory(CategoryCreationDto categoryCreationDto) {
        String normalized = CategoryNormalizer.normalize(categoryCreationDto.getName());
        if(categoryRepository.existsByNormalizedName(normalized)) {
            throw new IllegalArgumentException("Category with name " + normalized + " already exists.");
        }
        Category category = new Category();
        category.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
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
        CategoryDto categoryDto = categoryRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .map(categoryMapper::toDto)
                .orElse(null);

        if(categoryDto == null) {
            throw new ResourceNotFoundException("Category not found with id: " + id);
        } else {
            return categoryDto;
        }
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
            throw new ResourceNotFoundException("Category not found with id: " + id);
        }
        categoryRepository.deleteById(id);
    }
}