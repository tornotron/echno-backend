package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgTradeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.TradeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;

import java.util.List;
import java.util.UUID;

@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/inspections/web/trades")
@RequiredArgsConstructor
@Tag(
        name = "Inspection trades",
        description = "The trades an organization inspects against: the seeded catalogue copied "
                + "into the tenant on first use, plus any trade the organization defines itself. "
                + "Managing the list is the same authority as defining checklists. Trades are "
                + "deactivated, never deleted, so history keeps its reference."
)
public class TradeControllerWeb {

    private final TradeService service;

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List the organization's trades",
            description = "Active trades by default, in sort order then name. Pass includeInactive=true "
                    + "to see retired ones too.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The organization's trades"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<OrgTradeDto> list(@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return service.listOrgTrades(includeInactive);
    }

    @GetMapping("/catalogue")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List the shipped trade catalogue",
            description = "The global catalogue every organization's list is copied from. Read only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalogue"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<TradeCatalogueDto> catalogue() {
        return service.listCatalogue();
    }

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(summary = "Define a trade",
            description = "Adds an organization-defined trade. It appears in pickers at once and can "
                    + "back a checklist template.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Trade created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "409", description = "The organization already has a trade with that code")
    })
    public ResponseEntity<OrgTradeDto> create(@Valid @RequestBody CreateTradeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(summary = "Rename, regroup, reorder or deactivate a trade",
            description = "The code is immutable, including on catalogue copies. Deactivating hides "
                    + "the trade from pickers and leaves existing references valid.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trade updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No trade with the given id in the current tenant")
    })
    public OrgTradeDto update(@PathVariable UUID id, @Valid @RequestBody UpdateTradeRequest req) {
        return service.update(id, req);
    }
}
