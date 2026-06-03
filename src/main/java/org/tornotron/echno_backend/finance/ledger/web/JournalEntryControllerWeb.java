package org.tornotron.echno_backend.finance.ledger.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryDto;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/journal-entries")
@RequiredArgsConstructor
public class JournalEntryControllerWeb {

    private final JournalPostingService service;

    @PostMapping
    public ResponseEntity<JournalEntryDto> post(@Valid @RequestBody PostJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(req));
    }

    @GetMapping()
    public JournalEntryDto get(@RequestParam UUID id) {
        return service.findById(id);
    }

    @PostMapping("/reverse")
    public ResponseEntity<JournalEntryDto> reverse(
            @RequestParam UUID id,
            @Valid @RequestBody ReverseJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(id, req));
    }
}