package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.domain.DrawingEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DrawingEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.DrawingEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;

/**
 * Reads of the drawing register (design section 4.3), a number's revision chain, and the title-block crop
 * of a sheet, which the review screen shows beside each drawing candidate.
 */
@Service
@RequiredArgsConstructor
public class DrawingRegisterService {

    static final int MAX_PAGE_SIZE = 200;
    static final Duration CROP_URL_EXPIRY = Duration.ofMinutes(5);

    private final IngestPackageRepository packages;
    private final DrawingEntryRepository drawings;
    private final IngestDocumentRepository documents;
    private final FileStorageService storage;

    @Transactional(readOnly = true)
    public Page<DrawingEntryDto> list(UUID packageId, boolean currentOnly, int page, int size) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
        return drawings.findByPackage(packageId, currentOnly,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))).map(DrawingEntryDto::of);
    }

    /** Every revision of the row's number, oldest first, by following the chain's links. */
    @Transactional(readOnly = true)
    public List<DrawingEntryDto> revisions(UUID drawingId) {
        DrawingEntry row = drawings.findByIdScoped(drawingId)
                .orElseThrow(() -> new ResourceNotFoundException("No drawing " + drawingId));
        Map<UUID, DrawingEntry> byId = new HashMap<>();
        drawings.findChain(row.getPackageId(), row.getNumberKey()).forEach(d -> byId.put(d.getId(), d));
        DrawingEntry first = row;
        for (int guard = 0; first.getSupersedesId() != null && byId.containsKey(first.getSupersedesId())
                && guard < byId.size(); guard++) {
            first = byId.get(first.getSupersedesId());
        }
        List<DrawingEntryDto> out = new ArrayList<>();
        for (DrawingEntry d = first; d != null && out.size() < byId.size(); d = byId.get(d.getSupersededById())) {
            out.add(DrawingEntryDto.of(d));
            if (d.getSupersededById() == null) {
                break;
            }
        }
        return out;
    }

    /** A short-lived link to a sheet's title-block crop, as the worker wrote it. */
    @Transactional(readOnly = true)
    public DownloadUrlDto cropUrl(UUID documentId, int page) {
        IngestDocument doc = documents.findByIdScoped(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No document " + documentId));
        if (doc.getOutputPrefix() == null || page < 1) {
            throw new ResourceNotFoundException("No title-block crop for page " + page + " of " + doc.getFileName());
        }
        String key = doc.getOutputPrefix() + DocumentIngestionStorageLayout.titleBlockImage(page);
        if (!storage.objectExists(key)) {
            throw new ResourceNotFoundException("No title-block crop for page " + page + " of " + doc.getFileName());
        }
        return new DownloadUrlDto(storage.generateDownloadUrl(key, CROP_URL_EXPIRY), CROP_URL_EXPIRY.toSeconds());
    }
}
