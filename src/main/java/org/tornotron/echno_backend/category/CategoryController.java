package org.tornotron.echno_backend.category;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

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
        logger.info("Category Added Successfully");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Category Created Successfully"));
    }

    @GetMapping
    public ResponseEntity<List<CategoryDto>> readAllTasks(@RequestParam(defaultValue = "0") int pageNo,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        Page<CategoryDto> categories = categoryService.getAllCategories(pageNo, pageSize);
        logger.info("All Categories Retrieved Successfully");
        return new ResponseEntity<>(categories.getContent(), HttpStatus.OK);

    }

    @GetMapping("{id}")
    public ResponseEntity<?> readACategory(@PathVariable Long id) {
        CategoryDto categoryDto = categoryService.getACategory(id);
        return new ResponseEntity<>(categoryDto, HttpStatus.OK);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteACategory(@PathVariable Long id) {
        categoryService.deleteACategory(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Category with id: " + id + " deleted"));
    }
}
