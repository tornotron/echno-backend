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
import org.tornotron.echno_backend.category.dto.CategorySimpleDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

/**
 * REST controller for managing work categories.
 * Provides endpoints for creating, reading, and deleting categories.
 */
@RestController
@RequestMapping("api/v1/workCategories")
@Validated
public class CategoryController {

    private final CategoryService categoryService;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(CategoryController.class);

    /**
     * Constructs a CategoryController with the given CategoryService.
     *
     * @param categoryService The service for handling category-related business logic.
     */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * Creates a new category.
     *
     * @param categoryCreationDto DTO containing the details for the new category.
     * @return A {@link ResponseEntity} with a success message and HTTP status 201 (Created).
     */
    @PostMapping
    public ResponseEntity<CategorySimpleDto> createCategory(@Valid @RequestBody CategoryCreationDto categoryCreationDto) {
        logger.info("Category Added Successfully");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categoryService.addCategory(categoryCreationDto));
    }

    /**
     * Retrieves a paginated list of all categories.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of categories per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of category DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    public ResponseEntity<List<CategoryDto>> readAllTasks(@RequestParam(defaultValue = "0") int pageNo,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        Page<CategoryDto> categories = categoryService.getAllCategories(pageNo, pageSize);
        logger.info("All Categories Retrieved Successfully");
        return new ResponseEntity<>(categories.getContent(), HttpStatus.OK);

    }

    /**
     * Retrieves a single category by its ID.
     *
     * @param id The ID of the category to retrieve.
     * @return A {@link ResponseEntity} containing the category DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    public ResponseEntity<?> readACategory(@PathVariable Long id) {
        CategoryDto categoryDto = categoryService.getACategory(id);
        return new ResponseEntity<>(categoryDto, HttpStatus.OK);
    }

    /**
     * Deletes a category by its ID.
     *
     * @param id The ID of the category to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteACategory(@PathVariable Long id) {
        categoryService.deleteACategory(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Category with id: " + id + " deleted"));
    }
}