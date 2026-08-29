package org.tornotron.echno_backend.expense;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.expense.dto.ExpenseCreationDto;
import org.tornotron.echno_backend.expense.dto.ExpenseDto;
import org.tornotron.echno_backend.expense.dto.ExpenseUpdateDto;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/expenses/web")
@Tag(
        name = "Expenses",
        description = "Money spent against a project or the organization: materials, labour, equipment, "
                + "transport, utilities and the like, with an optional link to a project, vendor, employee, "
                + "invoice, payment or budget head. Read access requires tenant membership; creating, "
                + "updating and deleting are restricted to system-admin or project-manager."
)
public class ExpenseControllerWeb {

    private final ExpenseService expenseService;

    public ExpenseControllerWeb(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List all expenses",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expenses returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<ExpenseDto>> readAllExpenses() {
        return UnpagedResultCap.respond(expenseService.getPaginated(
                0, UnpagedResultCap.MAX_ROWS, null, null));
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List expenses, paginated and filterable",
            description = "Returns a single page of expenses. Supports a free-text search over the expense "
                    + "number and description, and filtering by status."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of expenses returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<ExpenseDto>> readAllExpensesPaginated(
            @Valid @ParameterObject PageQuery pageQuery,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        return new ResponseEntity<>(expenseService.getPaginated(pageQuery.getPageNo(), pageQuery.getPageSize(), search, status), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get an expense by id",
            description = "Returns a single expense with its category, amount and optional links."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No expense with the given id")
    })
    public ResponseEntity<ExpenseDto> readAnExpense(@PathVariable Long id) {
        return new ResponseEntity<>(expenseService.getById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create an expense",
            description = "Creates an expense with its category, amount and optional links. The expense "
                    + "number is generated by the server."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Expense created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "description or amount is missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ExpenseDto> createExpense(@Valid @RequestBody ExpenseCreationDto creationDto) {
        return new ResponseEntity<>(expenseService.create(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Update an expense",
            description = "Replaces the expense's editable details with the given payload. The expense "
                    + "number is immutable."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "description or amount is missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No expense with the given id")
    })
    public ResponseEntity<ExpenseDto> updateExpense(@PathVariable Long id,
                                                    @Valid @RequestBody ExpenseUpdateDto updateDto) {
        return new ResponseEntity<>(expenseService.update(id, updateDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete an expense",
            description = "Deletes the expense with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No expense with the given id")
    })
    public ResponseEntity<ApiResponse> deleteExpense(@PathVariable Long id) {
        expenseService.delete(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Expense with id: " + id + " has been deleted"));
    }
}
