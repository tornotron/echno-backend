package org.tornotron.echno_backend.category;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

@RestController
@RequestMapping("api/v1/categories")
@Validated
public class CategoryController {

    private final CategoryService categoryService;
    private static final Logger logger = LoggerFactory.getLogger(CategoryController.class);

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createCategory(@Valid @RequestBody CategoryCreationDto categoryCreationDto) {
        categoryService.addCategory(categoryCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Category Created Successfully"));
    }
}
