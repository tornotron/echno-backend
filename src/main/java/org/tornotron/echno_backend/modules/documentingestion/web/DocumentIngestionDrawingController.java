package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DrawingEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.DrawingRegisterService;

/**
 * The drawing register (design section 4.3): the drawings of a package by number and revision, a number's
 * revision chain, and the title-block crop of a sheet for the review screen.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionDrawingController {

    private final DrawingRegisterService register;

    @GetMapping("/packages/{packageId}/drawings")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List a package's drawing register",
            description = "One row per drawing number and revision, applied from accepted DRAWING candidates, with the "
                    + "title-block values as printed. With currentOnly=true, only each number's current revision. A DWG "
                    + "appears with status NOT_READ and the note \"Not read, add a PDF or DXF copy.\" Provenance for each "
                    + "field is under GET /provenance with target type DRAWING.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of drawings, by number"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public Page<DrawingEntryDto> listDrawings(@PathVariable UUID packageId,
                                              @RequestParam(defaultValue = "false") boolean currentOnly,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return register.list(packageId, currentOnly, page, size);
    }

    @GetMapping("/drawings/{drawingId}/revisions")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List every revision of a drawing's number, oldest first",
            description = "The revision chain the row belongs to, in revision order, with the current one marked. "
                    + "Older revisions are kept and link to the revision that replaced them.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The chain, oldest first"),
            @ApiResponse(responseCode = "404", description = "No such drawing in the current tenant")
    })
    public List<DrawingEntryDto> listDrawingRevisions(@PathVariable UUID drawingId) {
        return register.revisions(drawingId);
    }

    @GetMapping("/documents/{documentId}/title-blocks/{page}/image")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "Get a short-lived link to a sheet's title-block crop",
            description = "The image the file reader cropped from the sheet's title block, which the review screen "
                    + "shows beside each DRAWING candidate (payload sheet_page, crop_image) and register row "
                    + "(sheetPage, cropAvailable).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The link, valid for a few minutes"),
            @ApiResponse(responseCode = "404", description = "No such document in the current tenant, or no crop for that page")
    })
    public DownloadUrlDto titleBlockImage(@PathVariable UUID documentId, @PathVariable int page) {
        return register.cropUrl(documentId, page);
    }
}
