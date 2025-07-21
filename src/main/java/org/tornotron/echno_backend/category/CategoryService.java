package org.tornotron.echno_backend.category;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;

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
        category.setDescription(category.getDescription());
        category.setIcon(categoryCreationDto.getIcon());
        category.setImage(categoryCreationDto.getImage());
        Category savedCategory = categoryRepository.save(category);
        if(savedCategory.getId() == null) {
            throw new DatabaseOperationException("Category could not be created");
        }

    }
}
