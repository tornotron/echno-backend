package org.tornotron.echno_backend.finance.ledger.web;

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
@RequestMapping("/api/finance/journal-entries/web")
@RequiredArgsConstructor
public class JournalEntryControllerWeb {

    private final JournalPostingService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<JournalEntryDto> post(@Valid @RequestBody PostJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(req));
    }

    @GetMapping()
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public JournalEntryDto get(@RequestParam UUID id) {
        return service.findById(id);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<List<JournalEntryDto>> getAll(@RequestParam(defaultValue = "0") int pageNo,
                                        @RequestParam(defaultValue = "10") int pageSize) {
        Page<JournalEntryDto> journalEntries = service.findAll(pageNo,pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(journalEntries.getContent());
    }

    @PostMapping("/reverse")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<JournalEntryDto> reverse(
            @RequestParam UUID id,
            @Valid @RequestBody ReverseJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(id, req));
    }
}