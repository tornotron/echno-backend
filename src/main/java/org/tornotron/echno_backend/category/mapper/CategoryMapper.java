package org.tornotron.echno_backend.category.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.category.dto.CategorySimpleDto;

/** Maps {@link Category} to its full and simple DTOs. All fields by name. */
@Mapper(componentModel = "spring")
public interface CategoryMapper {
    CategoryDto toDto(Category category);
    CategorySimpleDto toSimpleDto(Category category);
}
