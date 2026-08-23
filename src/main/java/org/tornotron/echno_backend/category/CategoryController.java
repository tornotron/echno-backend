package org.tornotron.echno_backend.category;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(
        name = "Categories",
        description = "Work categories used to classify tasks, such as \"Reinforcement Steel\" or "
                + "\"Aggregates\". A category carries a name, description and optional icon and image. "
                + "Endpoints cover creating, listing and deleting categories. Access is gated by the "
                + "category authorities, with an admin authority that grants all operations."
)
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
    @PreAuthorize("hasAuthority('category:create') or hasAuthority('category:admin')")
    @Operation(
            summary = "Create a category",
            description = "Creates a work category, such as \"Cement & Binders\", available for tagging "
                    + "tasks. The name is checked against existing categories in the organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Category created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the category create or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A category with the same name already exists")
    })
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
    @PreAuthorize("hasAuthority('category:read') or hasAuthority('category:admin')")
    @Operation(
            summary = "List categories",
            description = "Returns a single page of work categories. The pageNo and pageSize parameters "
                    + "control paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of categories returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the category read or admin authority")
    })
    public ResponseEntity<List<CategoryDto>> readAllCategories(@RequestParam(defaultValue = "0") int pageNo,
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
    @PreAuthorize("hasAuthority('category:read') or hasAuthority('category:admin')")
    @Operation(
            summary = "Get a category by id",
            description = "Returns a single work category."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the category read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No category with the given id")
    })
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
    @PreAuthorize("hasAuthority('category:delete') or hasAuthority('category:admin')")
    @Operation(
            summary = "Delete a category",
            description = "Deletes the category with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the category delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No category with the given id")
    })
    public ResponseEntity<ApiResponse> deleteACategory(@PathVariable Long id) {
        categoryService.deleteACategory(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Category with id: " + id + " deleted"));
    }
}