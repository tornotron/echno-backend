package org.tornotron.echno_backend.documentReversal;

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
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalEligibilityDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRejectionRequest;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRequestDto;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/document-reversals")
@Tag(
        name = "Document Reversals",
        description = "Requests to reverse a site transfer, purchase order or goods received note, and "
                + "the decisions taken on them. A document is never deleted: whoever raised it asks for "
                + "it to be reversed, an administrator or project manager approves or rejects the "
                + "request with a reason, and on approval the document's stock movements are undone by "
                + "correcting ledger entries so every affected store returns to the balance it held "
                + "before. The original stays on the record marked reversed and linked to the request. "
                + "A document consumed downstream (a receipt against an order, an arrival against a "
                + "transfer, a payable against a receipt, stock issued since) cannot be reversed while "
                + "the downstream document stands; the request is refused with a message naming it. "
                + "Reads and raising a request are open to every member of the tenant, with the service "
                + "refusing anyone but the document's creator; approving and rejecting stay with "
                + "system-admin and project-manager, the roles that approve stock adjustments."
)
public class DocumentReversalController {

    private final DocumentReversalService reversalService;

    public DocumentReversalController(DocumentReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Request the reversal of a document",
            description = "Raises a pending reversal request on the named site transfer, purchase order or "
                    + "goods received note. The requester is taken from the session and must be the "
                    + "person who raised the document; nobody else, an administrator included, may ask "
                    + "on their behalf. Refused at once, with the blocker named, when the document cannot "
                    + "be reversed: it is cancelled or already reversed, a downstream document stands "
                    + "against it, stock it brought in has since been issued, or a request is already pending."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Reversal request raised"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The document cannot be reversed, a request is already pending, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or did not raise the document"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such document in this organization")
    })
    public ResponseEntity<DocumentReversalDto> requestReversal(@Valid @RequestBody DocumentReversalRequestDto request) {
        return new ResponseEntity<>(reversalService.request(request), HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List reversal requests, paginated",
            description = "Returns a page of reversal requests, most recently raised first, optionally "
                    + "filtered to one status. PENDING is the approvals queue."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of reversal requests returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<Page<DocumentReversalDto>> listReversals(
            @Valid @ParameterObject PageQuery pageQuery,
            @RequestParam(required = false) DocumentReversalStatus status) {
        return new ResponseEntity<>(
                reversalService.getAll(pageQuery.getPageNo(), pageQuery.getPageSize(), status), HttpStatus.OK);
    }

    @GetMapping("/by-document")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List the reversal requests raised on one document",
            description = "Every request ever raised on the document, most recent first, so the "
                    + "document's page can show the pending one, the approved one that undid it, or "
                    + "the refusals before it. Empty where none has been raised."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal requests on the document returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "documentType is not a reversible kind of document"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<DocumentReversalDto>> listReversalsByDocument(
            @RequestParam ReversibleDocumentType documentType,
            @RequestParam Long documentId) {
        return new ResponseEntity<>(reversalService.getByDocument(documentType, documentId), HttpStatus.OK);
    }

    @GetMapping("/eligibility")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Whether a document can be reversed, and whether the caller may ask",
            description = "Answers the two questions a document page needs before showing the request "
                    + "control: is anything blocking a reversal right now, and did the caller raise the "
                    + "document. The blocker, when there is one, is the same message the request would "
                    + "be refused with."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Eligibility returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such document in this organization")
    })
    public ResponseEntity<DocumentReversalEligibilityDto> eligibility(
            @RequestParam ReversibleDocumentType documentType,
            @RequestParam Long documentId) {
        return new ResponseEntity<>(reversalService.eligibility(documentType, documentId), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get a reversal request by id")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal request found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No reversal request with the given id")
    })
    public ResponseEntity<DocumentReversalDto> getReversal(@PathVariable Long id) {
        return new ResponseEntity<>(reversalService.getById(id), HttpStatus.OK);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Approve a reversal request and undo the document",
            description = "Writes one correcting ledger entry per balance row the document moved, for the "
                    + "opposite of what it wrote there, and moves each balance back; marks the document "
                    + "reversed and links it to this request; takes a reversed receipt's quantities back "
                    + "off its purchase order; and notifies the store keepers of every store that has "
                    + "stock to put back physically. Whoever raised the request cannot approve it unless "
                    + "they hold the system-admin role. The blockers are checked again here, because "
                    + "stock may have been issued since the request was raised."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal approved and posted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request is not pending, is being approved by its requester without the system-admin role, or the document is now blocked"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No reversal request with the given id")
    })
    public ResponseEntity<DocumentReversalDto> approveReversal(@PathVariable Long id) {
        return new ResponseEntity<>(reversalService.approve(id), HttpStatus.OK);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Reject a reversal request, recording why",
            description = "Refuses the request and keeps the refusal and its reason on the record. Nothing "
                    + "moves. The reason is required. The requester is told."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal request rejected"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "No reason was given, or the request is not pending"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No reversal request with the given id")
    })
    public ResponseEntity<DocumentReversalDto> rejectReversal(@PathVariable Long id,
                                                              @Valid @RequestBody DocumentReversalRejectionRequest request) {
        return new ResponseEntity<>(reversalService.reject(id, request.reason()), HttpStatus.OK);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Withdraw a pending reversal request",
            description = "Only the requester may withdraw their own request, and only while it is pending. "
                    + "The withdrawn row stays on the record."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal request withdrawn"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request is not pending"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or did not raise the request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No reversal request with the given id")
    })
    public ResponseEntity<DocumentReversalDto> cancelReversal(@PathVariable Long id) {
        return new ResponseEntity<>(reversalService.cancel(id), HttpStatus.OK);
    }
}
