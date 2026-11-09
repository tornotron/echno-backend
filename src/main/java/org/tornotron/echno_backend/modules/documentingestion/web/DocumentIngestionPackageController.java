package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.CreatePackageRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PackageDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.TenderProfileDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestDocumentService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;

/** Packages and their files: create a package, upload files into it, and follow each file's reading. */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionPackageController {

    private final IngestPackageService packageService;
    private final IngestDocumentService documentService;

    @PostMapping("/packages")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Start a package of tender files, optionally for an existing project")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Package created"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller lacks an upload role"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public ResponseEntity<PackageDto> createPackage(@Valid @RequestBody CreatePackageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(packageService.create(req));
    }

    @GetMapping("/packages")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List the organization's packages, newest first",
            description = "With projectId, only the packages tied to that project.")
    public Page<PackageDto> listPackages(@RequestParam(required = false) Long projectId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return packageService.list(projectId, page, size);
    }

    @GetMapping("/packages/{packageId}")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a package")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The package"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public PackageDto getPackage(@PathVariable UUID packageId) {
        return packageService.get(packageId);
    }

    @GetMapping("/packages/{packageId}/tender-profile")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a package's tender profile",
            description = "The accepted tender facts that a project has no field for: client, estimated value, EMD, "
                    + "tender fee, bid due date, bid validity, duration, consultant, engineer and scope, each as "
                    + "printed, with a typed value where the text reads plainly as one. Written only by applying "
                    + "accepted project fields; provenance for each field is under GET /provenance with target type "
                    + "TENDER_PROFILE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tender profile"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant, or no tender "
                    + "fact applied to it yet")
    })
    public TenderProfileDto getTenderProfile(@PathVariable UUID packageId) {
        return packageService.tenderProfile(packageId);
    }

    @GetMapping("/packages/{packageId}/documents")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List the files of a package in upload order, each with its latest reading job")
    public Page<DocumentDto> listDocuments(@PathVariable UUID packageId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return documentService.list(packageId, page, size);
    }

    @PostMapping("/packages/{packageId}/documents/presign")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Declare a file and get a URL to PUT it to",
            description = "Refuses a type Echno does not read, a file over the size limit, and a full package.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document and its upload URL"),
            @ApiResponse(responseCode = "400", description = "Type, size or package limit refused"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public DocumentUploadDto presign(@PathVariable UUID packageId, @Valid @RequestBody PresignDocumentRequest req) {
        return documentService.presign(packageId, req);
    }

    @PostMapping("/packages/{packageId}/documents/{documentId}/register")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Confirm an upload and queue the file for reading")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registered; queued unless the file is only stored"),
            @ApiResponse(responseCode = "400", description = "Wrong key, already registered, or nothing uploaded"),
            @ApiResponse(responseCode = "404", description = "No such document in the package")
    })
    public DocumentDto register(@PathVariable UUID packageId, @PathVariable UUID documentId,
                                @Valid @RequestBody RegisterDocumentRequest req) {
        return documentService.register(packageId, documentId, req);
    }

    @GetMapping("/documents/{documentId}")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a document with its latest reading job")
    public DocumentDto getDocument(@PathVariable UUID documentId) {
        return documentService.get(documentId);
    }

    @PostMapping("/documents/{documentId}/parse")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Read a document again after its reading failed")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Queued again"),
            @ApiResponse(responseCode = "400", description = "The document's reading has not failed")
    })
    public DocumentDto reparse(@PathVariable UUID documentId) {
        return documentService.reparse(documentId);
    }

    @GetMapping("/documents/{documentId}/download-url")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a short-lived link to download the source file")
    public DownloadUrlDto downloadUrl(@PathVariable UUID documentId) {
        return documentService.downloadUrl(documentId);
    }

    @GetMapping("/documents/{documentId}/pages/{page}/image")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a short-lived link to the image of a page",
            description = "The image the file reader made of one page of a PDF (pages numbered from 1), which the review "
                    + "screen draws a source's box over (SourceRef page and box).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The link, valid for a few minutes"),
            @ApiResponse(responseCode = "404", description = "No such document in the current tenant, or no image of that page")
    })
    public DownloadUrlDto pageImage(@PathVariable UUID documentId, @PathVariable int page) {
        return documentService.pageImageUrl(documentId, page);
    }
}
