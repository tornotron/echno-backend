package org.tornotron.echno_backend.inspection.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inspection.NcrStatus;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.inspection.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;
import org.tornotron.echno_backend.inspection.dtos.NcrRemarksRequest;
import org.tornotron.echno_backend.inspection.pdf.NcrReportPdfService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ncrs/web")
@RequiredArgsConstructor
@Tag(
        name = "Non-conformance reports",
        description = "The accountability trail over work that failed an inspection: who raised the "
                + "non-conformance, which site engineer owns it, who re-inspected it and who closed "
                + "it. The lifecycle runs open, assigned, corrective action complete, verified, "
                + "closed, with reject and reopen as the ways off that line; a step that is not part "
                + "of it is refused rather than recorded. All endpoints are tenant scoped, and the "
                + "authority is split along the same line as the lifecycle: the QA engineer and the "
                + "safety officer raise and assign, the site engineer reports the corrective work "
                + "done, and verifying, rejecting, reopening and closing need the sign-off role for "
                + "that report's own discipline, so a safety officer cannot close a quality report or "
                + "the other way round."
)
public class NcrControllerWeb {

    private final NcrService service;
    private final NcrReportPdfService reportService;

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canRaiseNcrs()")
    @Operation(
            summary = "Raise a non-conformance report",
            description = "Raises an NCR against an inspection, and against one of its defects when "
                    + "one is named. The NCR number, the type (from the inspection's category) and "
                    + "the raiser are all set server-side. Naming a site engineer assigns it in the "
                    + "same step."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "NCR raised"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such inspection in the current tenant, or the "
                    + "named defect does not belong to it")
    })
    public ResponseEntity<NcrDto> create(@Valid @RequestBody CreateNcrRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Get a non-conformance report by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List non-conformance reports",
            description = "Returns a page of NCRs in the current tenant. Every parameter is an "
                    + "optional filter. Setting open=true returns the punch list: every NCR that has "
                    + "not been closed, whatever stage it has reached."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching NCRs"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<NcrDto> list(@RequestParam(required = false) UUID inspectionId,
                             @RequestParam(required = false) NcrType type,
                             @RequestParam(required = false) NcrStatus status,
                             @RequestParam(required = false) Long siteEngineerId,
                             @RequestParam(required = false) Boolean open,
                             Pageable pageable) {
        return service.findAll(inspectionId, type, status, siteEngineerId, open, pageable);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("@inspectionSecurity.canRaiseNcrs()")
    @Operation(
            summary = "Assign a non-conformance report",
            description = "Hands the corrective work to a site engineer, or moves it to a different "
                    + "one. Allowed from open, rejected and reopened."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR assigned"),
            @ApiResponse(responseCode = "400", description = "The NCR is at a stage it cannot be assigned from"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto assign(@PathVariable UUID id, @Valid @RequestBody AssignNcrRequest req) {
        return service.assign(id, req);
    }

    @PostMapping("/{id}/corrective-action-complete")
    @PreAuthorize("@inspectionSecurity.canReportCorrectiveAction()")
    @Operation(
            summary = "Report the corrective action complete",
            description = "The site engineer reports the work done and the NCR ready for "
                    + "re-inspection. This is as far as the assignee takes it: accepting the work "
                    + "and closing the report are separate steps."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Corrective action recorded"),
            @ApiResponse(responseCode = "400", description = "The NCR is not assigned, so there is no work to report"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto markCorrectiveActionComplete(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) NcrRemarksRequest req) {
        return service.markCorrectiveActionComplete(id, remarksOf(req));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("@inspectionSecurity.canSignOffNcr(#id)")
    @Operation(
            summary = "Verify the corrective action",
            description = "Records that the corrective work was re-inspected and accepted."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR verified"),
            @ApiResponse(responseCode = "400", description = "The corrective action has not been reported complete"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto verify(@PathVariable UUID id,
                         @Valid @RequestBody(required = false) NcrRemarksRequest req) {
        return service.verify(id, remarksOf(req));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@inspectionSecurity.canSignOffNcr(#id)")
    @Operation(
            summary = "Reject the corrective action",
            description = "Records that the corrective work was re-inspected and not accepted. The "
                    + "NCR goes back to the site engineer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR rejected"),
            @ApiResponse(responseCode = "400", description = "The corrective action has not been reported complete"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto reject(@PathVariable UUID id,
                         @Valid @RequestBody(required = false) NcrRemarksRequest req) {
        return service.reject(id, remarksOf(req));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("@inspectionSecurity.canSignOffNcr(#id)")
    @Operation(
            summary = "Reopen a non-conformance report",
            description = "Records that the same non-conformance has come back after it was verified "
                    + "or closed. It is recorded on the original report, because that report's "
                    + "history is the evidence that it recurred."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR reopened"),
            @ApiResponse(responseCode = "400", description = "The NCR has not been verified or closed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto reopen(@PathVariable UUID id,
                         @Valid @RequestBody(required = false) NcrRemarksRequest req) {
        return service.reopen(id, remarksOf(req));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("@inspectionSecurity.canSignOffNcr(#id)")
    @Operation(
            summary = "Close a non-conformance report",
            description = "Closes a verified NCR and records who closed it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "NCR closed"),
            @ApiResponse(responseCode = "400", description = "The NCR has not been verified"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public NcrDto close(@PathVariable UUID id) {
        return service.close(id);
    }

    /** The remark, when a body was sent at all. Every note on the lifecycle is optional. */
    private static String remarksOf(NcrRemarksRequest req) {
        return req == null ? null : req.remarks();
    }

    /*
     * Mapped ahead of /{id}/pdf on purpose. Spring's path-pattern comparator sorts a
     * literal segment before a template variable, so /punch-list/pdf wins the match and
     * "punch-list" is never offered to the UUID converter.
     */
    @GetMapping("/punch-list/pdf")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Download the punch list",
            description = "Renders every non-conformance that is not yet closed, newest first. This "
                    + "is the same query the open=true filter on the listing answers, rendered rather "
                    + "than recomputed. The inspectionId, type and siteEngineerId parameters narrow "
                    + "it the same way they narrow the listing. At most 500 rows print; the document "
                    + "states the true total and flags itself when it is showing only part of it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "500", description = "PDF rendering failed")
    })
    public ResponseEntity<byte[]> downloadPunchList(
            @RequestParam(required = false) UUID inspectionId,
            @RequestParam(required = false) NcrType type,
            @RequestParam(required = false) Long siteEngineerId) throws IOException {
        return asAttachment(reportService.renderPunchList(inspectionId, type, siteEngineerId));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Download a non-conformance report",
            description = "Renders one report with its full accountability trail: who raised it and "
                    + "when, which site engineer owns it, the corrective action they reported and the "
                    + "date they reported it, who accepted it and when, and who closed it and when. A "
                    + "stage that has not happened prints as blank rather than being hidden, so a "
                    + "reader can see how far the report got."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant"),
            @ApiResponse(responseCode = "500", description = "PDF rendering failed")
    })
    public ResponseEntity<byte[]> downloadReport(@PathVariable UUID id) throws IOException {
        return asAttachment(reportService.render(id));
    }

    private static ResponseEntity<byte[]> asAttachment(RenderedReport report) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.documentName() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(report.content());
    }
}
