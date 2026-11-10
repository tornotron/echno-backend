package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConflictChoiceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionDiffDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionRequest;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.review.ConflictService;
import org.tornotron.echno_backend.modules.documentingestion.service.RevisionService;

/** Revisions and corrigenda: the revision chain, the revised-BOQ comparison, and the choice on a conflicting change. */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionRevisionController {

    private final RevisionService revisions;
    private final ConflictService conflicts;

    @PutMapping("/documents/{documentId}/revision")
    @PreAuthorize("@access.can('document-ingestion', 'upload')")
    @Operation(summary = "Mark a document as a revision of an earlier one",
            description = "Records that this file replaces an earlier file of the same package, such as a revised BOQ "
                    + "issued with a corrigendum. A chain that would loop is refused.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document, with the file it replaces"),
            @ApiResponse(responseCode = "400", description = "The earlier file is in another package, is this file, or "
                    + "would make a loop"),
            @ApiResponse(responseCode = "404", description = "No such document in the current tenant")
    })
    public DocumentDto markRevision(@PathVariable UUID documentId, @Valid @RequestBody RevisionRequest req) {
        return revisions.markRevision(documentId, req);
    }

    @PostMapping("/documents/{documentId}/revision-diff")
    @PreAuthorize("@access.can('document-ingestion', 'upload')")
    @Operation(summary = "Compare a revised BOQ with the file it replaces",
            description = "Compares the BOQ rows read from this file with those of the file it replaces, by item code, "
                    + "with no AI service. Each changed description, unit, quantity or rate becomes a CHANGE candidate "
                    + "citing the revised cell, with the quantity variance and money impact worked out; an item in only "
                    + "one of the two files is a change flagged as added or removed. Run again, it adds nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What the comparison proposed"),
            @ApiResponse(responseCode = "400", description = "The file is not marked as a revision, or its BOQ has not "
                    + "been read"),
            @ApiResponse(responseCode = "404", description = "No such document in the current tenant")
    })
    public RevisionDiffDto diff(@PathVariable UUID documentId) {
        return revisions.diff(documentId);
    }

    @PostMapping("/candidates/{candidateId}/conflict")
    @PreAuthorize("@access.can('document-ingestion', 'review') "
            + "or @access.can('document-ingestion', 'review-quality') "
            + "or @access.can('document-ingestion', 'review-drawings')")
    @Operation(summary = "Choose on a change whose target was edited by hand",
            description = "A change is not applied over a record edited by hand since the value was applied; the apply "
                    + "step names the three values instead. APPLY_CHANGE writes the change on the next apply; KEEP_RECORD "
                    + "rejects it and keeps the record.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The change, accepted again or rejected"),
            @ApiResponse(responseCode = "400", description = "The candidate is not a change that failed to apply"),
            @ApiResponse(responseCode = "404", description = "No such candidate in the current tenant")
    })
    public CandidateDto chooseOnConflict(@PathVariable UUID candidateId, @Valid @RequestBody ConflictChoiceRequest req) {
        return conflicts.choose(candidateId, req);
    }
}
