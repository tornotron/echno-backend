package org.tornotron.echno_backend.finance.ledger.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryDto;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/journal-entries/web")
@RequiredArgsConstructor
@Tag(
        name = "Journal Entries",
        description = "Double-entry journal entries posted to the ledger. Each entry carries two or more "
                + "lines whose total debits must equal total credits. A posted entry is immutable and can "
                + "only be undone by posting a reversing entry. All endpoints are tenant scoped and limited "
                + "to system administrators and project managers."
)
public class JournalEntryControllerWeb {

    private final JournalPostingService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Post a journal entry",
            description = "Posts a balanced journal entry to the ledger. The supplied lines must balance, "
                    + "with total debits equal to total credits, and every referenced account must be an "
                    + "active postable account. The entry number is assigned by the system."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Journal entry posted"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the entry does not balance"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "A referenced account does not exist in the current tenant")
    })
    public ResponseEntity<JournalEntryDto> post(@Valid @RequestBody PostJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(req));
    }

    @GetMapping()
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a journal entry by id",
            description = "Returns a single journal entry, including its lines, by its unique id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journal entry found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No journal entry with the given id in the current tenant")
    })
    public JournalEntryDto get(@RequestParam UUID id) {
        return service.findById(id);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List journal entries",
            description = "Returns a page of journal entries in the current tenant. Use pageNo and pageSize "
                    + "to page through the results."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of journal entries"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<JournalEntryDto>> getAll(@RequestParam(defaultValue = "0") int pageNo,
                                        @RequestParam(defaultValue = "10") int pageSize) {
        Page<JournalEntryDto> journalEntries = service.findAll(pageNo,pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(journalEntries.getContent());
    }

    @PostMapping("/reverse")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Reverse a journal entry",
            description = "Posts a reversing entry that negates the original entry, swapping its debits and "
                    + "credits, and records the supplied reason. The original entry is left in place and "
                    + "linked to its reversal. An entry that has already been reversed cannot be reversed again."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversing entry posted"),
            @ApiResponse(responseCode = "400", description = "Entry cannot be reversed, for example it is already reversed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No journal entry with the given id in the current tenant")
    })
    public ResponseEntity<JournalEntryDto> reverse(
            @RequestParam UUID id,
            @Valid @RequestBody ReverseJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(id, req));
    }
}