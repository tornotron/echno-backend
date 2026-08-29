package org.tornotron.echno_backend.labour;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.labour.dto.LabourCreationDto;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.dto.LabourSimpleDto;
import org.tornotron.echno_backend.labour.dto.LabourUpdateDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/labour/web")
@Validated
@Tag(
        name = "Labour",
        description = "Contract and daily-wage workforce records for the organization: masons, bar "
                + "benders, electricians and similar site labour, with employment type, skill level, "
                + "pay rate and bank details. Endpoints cover creating a labour record, listing, lookup "
                + "by id, partial update and deletion, all restricted to admin and HR roles."
)
public class LabourControllerWeb {

    private final LabourService labourService;

    public LabourControllerWeb(LabourService labourService) {
        this.labourService = labourService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Create a labour record",
            description = "Adds a new labour record to the current tenant organization, for example a "
                    + "mason or bar bender engaged on daily wage or contract terms."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Labour record created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<LabourSimpleDto> createLabour(@Valid @RequestBody LabourCreationDto labourCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(labourService.createLabour(labourCreationDto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List labour records",
            description = "Returns a single page of labour records. The pageNo and pageSize parameters "
                    + "control paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of labour records returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<LabourDto>> getAllLabours(@Valid @ParameterObject PageQuery pageQuery) {
        return ResponseEntity.status(HttpStatus.OK).body(labourService.getAllLabours(pageQuery.getPageNo(), pageQuery.getPageSize()).getContent());
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a labour record by id",
            description = "Returns a single labour record with its employment, pay and bank detail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Labour record found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No labour record with the given id")
    })
    public ResponseEntity<LabourDto> getALabour(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(labourService.getALabour(id));
    }

    @PatchMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Partially update a labour record",
            description = "Applies the supplied fields as a partial update to an existing labour record. "
                    + "Fields left null in the request body are left unchanged."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Labour record updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No labour record with the given id")
    })
    public ResponseEntity<ApiResponse> partialUpdateALabour(@Valid @RequestBody LabourUpdateDto updates, @PathVariable Long id) {
        labourService.partialUpdateALabour(updates, id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Labour with id: " + id + " updated"));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Delete a labour record",
            description = "Deletes the labour record with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Labour record deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No labour record with the given id")
    })
    public ResponseEntity<ApiResponse> deleteALabour(@PathVariable Long id) {
        labourService.deleteALabour(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Labour with id: " + id + " has been deleted"));
    }
}
