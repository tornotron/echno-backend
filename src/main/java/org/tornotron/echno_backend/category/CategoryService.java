package org.tornotron.echno_backend.category;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;


@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public CategoryDto convertToDto(Category category) {
        CategoryDto dto = new CategoryDto();
        dto.setId(category.getId());
        dto.setName(category.getName());
        dto.setDescription(category.getDescription());
        dto.setIcon(category.getIcon());
        dto.setImage(category.getImage());
        return dto;
    }

    public void addCategory(CategoryCreationDto categoryCreationDto) {
        Category category = new Category();
        category.setName(categoryCreationDto.getName());
        category.setDescription(categoryCreationDto.getDescription());
        category.setIcon(categoryCreationDto.getIcon());
        category.setImage(categoryCreationDto.getImage());
        Category savedCategory = categoryRepository.save(category);
        if(savedCategory.getId() == null) {
            throw new DatabaseOperationException("Category could not be created");
        }
    }

    public Page<CategoryDto> getAllCategories(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return categoryRepository.findAll(pageable)
                .map(this::convertToDto);
    }

    @Transactional(readOnly = true)
    public CategoryDto getACategory(Long id) {
        CategoryDto categoryDto = categoryRepository.findById(id)
                .map(this::convertToDto)
                .orElse(null);

        if(categoryDto == null) {
            throw new ResourceNotFoundException("Category not found with id: " + id);
        } else {
            return categoryDto;
        }
    }

    public void deleteACategory(Long id) {
        if(!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category not found with id: " + id);
        }
        categoryRepository.deleteById(id);
    }
}
