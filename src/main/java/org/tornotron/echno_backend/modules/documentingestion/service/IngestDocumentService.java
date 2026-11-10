package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.domain.ClassMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobType;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ParseJobDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Files into a package. The source never passes through the API: presign creates the document and
 * hands the browser a PUT for a key composed here, register confirms the object is there and queues
 * the worker. The key the browser sends back must be the one it was given, so a caller cannot point
 * a document at another package's or another tenant's object.
 */
@Service
@RequiredArgsConstructor
public class IngestDocumentService {

    static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(60);
    static final Duration DOWNLOAD_URL_EXPIRY = Duration.ofMinutes(5);
    static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    static final int MAX_PAGE_SIZE = 200;

    private final IngestPackageService packageService;
    private final IngestDocumentRepository documents;
    private final ParseJobRepository jobs;
    private final FileStorageService fileStorageService;
    private final AttachmentService attachmentService;
    private final UserContextService userContextService;
    private final DocumentIngestionLimits limits;

    @Transactional
    public DocumentUploadDto presign(UUID packageId, PresignDocumentRequest req) {
        IngestPackage pkg = packageService.requireOpenPackage(packageId);
        String fileName = cleanFileName(req.fileName());
        DocumentFileType type = typeOf(fileName);
        long cap = type == DocumentFileType.ZIP ? limits.maxZipBytes() : limits.maxFileBytes();
        if (req.fileSize() > cap) {
            throw new InvalidRequestException(fileName + " is " + mb(req.fileSize()) + " MB; the limit for a "
                    + type + " is " + mb(cap) + " MB.");
        }
        if (documents.countInPackage(packageId) >= limits.maxDocumentsPerPackage()) {
            throw new InvalidRequestException("A package holds at most " + limits.maxDocumentsPerPackage()
                    + " files. Start a second package for the rest.");
        }

        IngestDocument doc = new IngestDocument();
        doc.setOrganization(pkg.getOrganization());
        doc.setPackageId(packageId);
        doc.setFileName(fileName);
        doc.setFileType(type);
        doc.setContentType(req.contentType() == null || req.contentType().isBlank()
                ? DEFAULT_CONTENT_TYPE : req.contentType().trim());
        doc.setSizeBytes(req.fileSize());
        doc.setStatus(DocumentStatus.AWAITING_UPLOAD);
        doc.setCreatedBy(userContextService.getCurrentUserId());
        doc.setUpdatedBy(doc.getCreatedBy());
        // The key needs the id, which is assigned at persist.
        doc.setSourceKey("pending");
        doc = documents.saveAndFlush(doc);
        Long orgId = pkg.getOrganization().getId();
        doc.setSourceKey(DocumentIngestionStorageLayout.sourceKey(orgId, packageId, doc.getId()));
        documents.save(doc);

        PresignedUpload upload = fileStorageService.generateUploadUrlForKey(doc.getSourceKey(), doc.getContentType(),
                UPLOAD_URL_EXPIRY);
        return new DocumentUploadDto(doc.getId(), upload);
    }

    @Transactional
    public DocumentDto register(UUID packageId, UUID documentId, RegisterDocumentRequest req) {
        packageService.requireOpenPackage(packageId);
        IngestDocument doc = requireDocumentInPackage(packageId, documentId);
        if (!doc.getSourceKey().equals(req.key())) {
            throw new InvalidRequestException("That key was not issued for this document");
        }
        if (doc.getStatus() != DocumentStatus.AWAITING_UPLOAD) {
            throw new InvalidRequestException(doc.getFileName() + " is already registered");
        }
        if (!fileStorageService.objectExists(doc.getSourceKey())) {
            throw new InvalidRequestException("No uploaded file was found for " + doc.getFileName()
                    + "; the upload may not have completed");
        }
        doc.setAttachmentId(attachmentService.registerUploads(
                List.of(new RegisterUploadRequest(doc.getSourceKey(), doc.getFileName(), doc.getContentType(),
                        doc.getSizeBytes())),
                DocumentIngestionStorageLayout.ownerOf(doc.getId()),
                DocumentIngestionStorageLayout.ATTACHMENT_FOLDER).getFirst().getId());
        doc.setOutputPrefix(DocumentIngestionStorageLayout.outputPrefix(
                doc.getOrganization().getId(), packageId, doc.getId()));
        if (doc.getFileType() == DocumentFileType.DWG) {
            doc.setStatus(DocumentStatus.STORED_ONLY);
            doc.setError("DWG files are stored and listed but not read. Add a PDF or DXF copy to have it read.");
            doc.setDocClass(DocClass.DRAWING.name());
            doc.setClassMethod(ClassMethod.DETERMINISTIC);
            doc.setClassReason("A drawing file.");
        } else {
            enqueue(doc);
        }
        documents.save(doc);
        return toDto(doc);
    }

    /** Queues a fresh parse of a document whose last parse failed. The failed row stays as it was. */
    @Transactional
    public DocumentDto reparse(UUID documentId) {
        IngestDocument doc = requireDocument(documentId);
        packageService.requireOpenPackage(doc.getPackageId());
        if (doc.getStatus() != DocumentStatus.FAILED || doc.getAttachmentId() == null && doc.getParentDocumentId() == null) {
            throw new InvalidRequestException("Only a document whose reading failed can be read again");
        }
        doc.setError(null);
        enqueue(doc);
        documents.save(doc);
        return toDto(doc);
    }

    @Transactional(readOnly = true)
    public Page<DocumentDto> list(UUID packageId, int page, int size) {
        packageService.requirePackage(packageId);
        return documents.findByPackage(packageId,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public DocumentDto get(UUID documentId) {
        return toDto(requireDocument(documentId));
    }

    @Transactional(readOnly = true)
    public DownloadUrlDto downloadUrl(UUID documentId) {
        IngestDocument doc = requireDocument(documentId);
        if (doc.getStatus() == DocumentStatus.AWAITING_UPLOAD) {
            throw new InvalidRequestException(doc.getFileName() + " has not been uploaded yet");
        }
        return new DownloadUrlDto(fileStorageService.generateDownloadUrl(doc.getSourceKey(), DOWNLOAD_URL_EXPIRY),
                DOWNLOAD_URL_EXPIRY.toSeconds());
    }

    /**
     * A short-lived link to the worker's image of one page of a document, which the review screen draws a
     * source box over. The document is looked up in the current tenant first and the key is composed here
     * from its output prefix, so a client never names an object.
     */
    @Transactional(readOnly = true)
    public DownloadUrlDto pageImageUrl(UUID documentId, int page) {
        IngestDocument doc = requireDocument(documentId);
        if (doc.getOutputPrefix() == null || page < 1) {
            throw new ResourceNotFoundException("No image of page " + page + " of " + doc.getFileName());
        }
        String key = doc.getOutputPrefix() + DocumentIngestionStorageLayout.pageImage(page);
        if (!fileStorageService.objectExists(key)) {
            throw new ResourceNotFoundException("No image of page " + page + " of " + doc.getFileName());
        }
        return new DownloadUrlDto(fileStorageService.generateDownloadUrl(key, DOWNLOAD_URL_EXPIRY),
                DOWNLOAD_URL_EXPIRY.toSeconds());
    }

    private void enqueue(IngestDocument doc) {
        ParseJob job = new ParseJob();
        job.setOrganization(doc.getOrganization());
        job.setPackageId(doc.getPackageId());
        job.setDocumentId(doc.getId());
        job.setJobType(doc.getFileType() == DocumentFileType.ZIP ? ParseJobType.UNPACK : ParseJobType.PARSE);
        job.setStatus(ParseJobStatus.QUEUED);
        job.setSourceKey(doc.getSourceKey());
        job.setSourceFileName(doc.getFileName());
        job.setOutputPrefix(DocumentIngestionStorageLayout.outputPrefix(
                doc.getOrganization().getId(), doc.getPackageId(), doc.getId()));
        job.setQueuedAt(LocalDateTime.now());
        jobs.save(job);
        doc.setStatus(DocumentStatus.QUEUED);
    }

    private IngestDocument requireDocument(UUID documentId) {
        return documents.findByIdScoped(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No document " + documentId));
    }

    private IngestDocument requireDocumentInPackage(UUID packageId, UUID documentId) {
        IngestDocument doc = requireDocument(documentId);
        if (!doc.getPackageId().equals(packageId)) {
            throw new ResourceNotFoundException("No document " + documentId + " in package " + packageId);
        }
        return doc;
    }

    private DocumentDto toDto(IngestDocument doc) {
        return DocumentDto.of(doc, jobs.findLatestForDocument(doc.getId()).map(ParseJobDto::of).orElse(null));
    }

    /** The last path segment, trimmed, so a name can never carry a directory into a message or a key. */
    static String cleanFileName(String raw) {
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new InvalidRequestException("The file needs a name");
        }
        return name;
    }

    static DocumentFileType typeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            throw new InvalidRequestException(fileName + " is an old Word file. Save it as DOCX or PDF and upload that.");
        }
        if (lower.endsWith(".rar") || lower.endsWith(".7z")) {
            throw new InvalidRequestException(fileName + " is a RAR or 7z archive. Upload a ZIP, or the files themselves.");
        }
        return DocumentFileType.ofFileName(fileName).orElseThrow(() -> new InvalidRequestException(
                fileName + " is not a type Echno reads. Upload PDF, XLSX, XLS, DOCX, DXF, DWG or ZIP."));
    }

    private static long mb(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }
}
