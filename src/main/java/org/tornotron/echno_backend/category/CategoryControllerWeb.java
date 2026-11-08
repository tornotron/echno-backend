package org.tornotron.echno_backend.category;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.category.dto.CategorySimpleDto;
import org.tornotron.echno_backend.category.dto.WorkSubcategoryDto;
import org.tornotron.echno_backend.common.pagination.PageQueryLookup;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/category/web")
@Tag(
        name = "Categories (Web)",
        description = "Web-console counterpart of the category API, gated by the projects permissions "
                + "of the access matrix instead of a flat authority: each endpoint needs the one named "
                + "for its action (read, create or delete). A category belongs to its organization and "
                + "to no project, so only a grant covering every record of the organization reaches it. "
                + "Covers creating, listing and deleting work categories such as "
                + "\"Reinforcement Steel\" or \"Aggregates\"."
)
public class CategoryControllerWeb {

    private final CategoryService categoryService;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(CategoryControllerWeb.class);

    /**
     * Constructs a CategoryController with the given CategoryService.
     *
     * @param categoryService The service for handling category-related business logic.
     */
    public CategoryControllerWeb(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * Creates a new category.
     *
     * @param categoryCreationDto DTO containing the details for the new category.
     * @return A {@link ResponseEntity} with a success message and HTTP status 201 (Created).
     */
    @PostMapping
    @PreAuthorize("@access.can('projects', 'create')")
    @Operation(
            summary = "Create a category",
            description = "Creates a work category, such as \"Cement & Binders\", available for tagging "
                    + "tasks. The name is checked against existing categories in the organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Category created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not make this change to categories"),
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
     * @param pageQuery Page index and page size, bounded by {@link PageQueryLookup}, which serves the whole list when no size is named.
     * @return A {@link ResponseEntity} containing the list of category DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("@access.can('projects', 'read')")
    @Operation(
            summary = "List categories",
            description = "Returns the organization's work categories for the task form's dropdown. With no "
                    + "pageSize the whole list is returned (up to 500); pageNo and pageSize still page it "
                    + "when given. Only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of categories returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read categories")
    })
    public ResponseEntity<List<CategoryDto>> readAllCategories(@Valid @ParameterObject PageQueryLookup pageQuery) {
        Page<CategoryDto> categories = categoryService.getAllCategories(pageQuery.getPageNo(), pageQuery.getPageSize());
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
    @PreAuthorize("@access.can('projects', 'read')")
    @Operation(
            summary = "Get a category by id",
            description = "Returns a single work category."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read categories"),
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
    @PreAuthorize("@access.can('projects', 'delete')")
    @Operation(
            summary = "Delete a category",
            description = "Deletes the category with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not make this change to categories"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No category with the given id")
    })
    public ResponseEntity<ApiResponse> deleteACategory(@PathVariable Long id) {
        categoryService.deleteACategory(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Category with id: " + id + " deleted"));
    }



    /**
     * Lists the sub-categories of one work category, for the second dropdown on the task form.
     *
     * @param id The work category.
     * @return Its sub-categories in dropdown order.
     */
    @GetMapping("{id}/subcategories")
    @PreAuthorize("@access.can('projects', 'read')")
    @Operation(
            summary = "List a category's sub-categories",
            description = "Returns the sub-categories of a work category in dropdown order, such as "
                    + "Excavation and Backfilling under Earthwork. A task stores its sub-category as text, "
                    + "so this list is what the form suggests; a sub-category typed in by hand is not added to it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sub-categories returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read categories"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No category with the given id")
    })
    public ResponseEntity<List<WorkSubcategoryDto>> readSubcategories(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.getSubcategories(id));
    }
}
