package org.tornotron.echno_backend.finance.ledger.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.CustomerDto;
import org.tornotron.echno_backend.finance.ledger.dtos.UpdateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.service.CustomerService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/customers/web")
@RequiredArgsConstructor
@Tag(
        name = "Customers",
        description = "Customers billed by the ledger, holding their tax registration, contact and billing "
                + "details along with a credit limit and payment terms. All endpoints are tenant scoped and "
                + "limited to system administrators and project managers."
)
public class CustomerControllerWeb {

    private final CustomerService service;

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Search customers",
            description = "Returns a page of customers in the current tenant. The optional name parameter "
                    + "filters by customer name; omitting it returns all customers, subject to paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of matching customers"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<CustomerDto> list(@RequestParam(required = false) String name, Pageable pageable) {
        return service.search(name, pageable).getContent();
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a customer by id",
            description = "Returns a single customer, including the billing address, by its unique id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No customer with the given id in the current tenant")
    })
    public CustomerDto get(@RequestParam UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a customer",
            description = "Creates a new customer from the supplied details. The customer code must be "
                    + "unique within the tenant."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update a customer",
            description = "Updates the editable details of an existing customer. The customer code is fixed "
                    + "at creation and is not changed here."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No customer with the given id in the current tenant")
    })
    public CustomerDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Deactivate a customer",
            description = "Marks a customer inactive so it can no longer be selected on new documents. "
                    + "Existing records that reference the customer are left unchanged."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Customer deactivated"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No customer with the given id in the current tenant")
    })
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

}
