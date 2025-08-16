package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.dto.CategoryDto;

@Component
public class CategoryDtoConvertor {

    public static CategoryDto convertCategoryToDto(Category category) {
        CategoryDto dto = new CategoryDto();
        dto.setId(category.getId());
        dto.setName(category.getName());
        dto.setDescription(category.getDescription());
        dto.setIcon(category.getIcon());
        dto.setImage(category.getImage());
        return dto;
    }

}
