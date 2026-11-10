package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyEvidence;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;
import org.tornotron.echno_backend.modules.documentingestion.domain.EvidenceSection;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualificationRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyEvidenceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyProfileDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyProfileRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ComplianceMatrixDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.EvidenceUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignEvidenceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualificationRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RequirementStatusRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationRequirementType;
import org.tornotron.echno_backend.modules.documentingestion.repository.CompanyEvidenceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.CompanyProfileRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.QualificationRequirementEntryRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Qualification and bidding (design section 4.7): the company profile an admin types in, its evidence
 * files, the package's accepted qualification requirements, a person's status on those in text, and
 * the Tender Compliance Matrix that sets each requirement beside the profile.
 *
 * <p>The matrix is shown only while a package is at stage {@code TENDER}. It shows matches and gaps
 * and makes no bid or no-bid recommendation. Nothing outside the module is written, apart from the
 * attachment record each evidence file gets, as package documents do.
 */
@Service
@RequiredArgsConstructor
public class QualificationService {

    public static final String NOTICE = "The tender's stated requirements beside the company profile as entered. "
            + "Echno does not recommend whether to bid; that decision is yours.";
    public static final String HIDDEN_NOTICE = "The package is at stage AWARDED. The compliance matrix is shown only "
            + "while a tender is being bid.";

    static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(60);
    static final Duration DOWNLOAD_URL_EXPIRY = Duration.ofMinutes(5);
    static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    static final int MAX_REQUIREMENTS = 500;
    static final int MAX_EVIDENCE = 500;
    static final Set<String> EVIDENCE_TYPES = Set.of("pdf", "jpg", "jpeg", "png", "webp", "xlsx", "xls", "docx");

    private final CompanyProfileRepository profiles;
    private final CompanyEvidenceRepository evidence;
    private final QualificationRequirementEntryRepository requirements;
    private final IngestPackageRepository packages;
    private final FileStorageService fileStorageService;
    private final AttachmentService attachmentService;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final DocumentIngestionLimits limits;

    @Transactional(readOnly = true)
    public CompanyProfileDto profile() {
        return CompanyProfileDto.of(current(), uploadedEvidence().stream().map(CompanyEvidenceDto::of).toList());
    }

    /** Replaces the profile's fields as given; evidence files are kept. */
    @Transactional
    public CompanyProfileDto saveProfile(CompanyProfileRequest req) {
        List<CompanyProfile.TurnoverYear> turnover = req.turnover() == null ? List.of() : req.turnover();
        Set<Integer> years = new HashSet<>();
        for (CompanyProfile.TurnoverYear y : turnover) {
            if (!years.add(Integer.parseInt(y.financialYear().substring(0, 4)))) {
                throw new InvalidRequestException("Turnover for " + y.financialYear() + " is given twice");
            }
        }
        CompanyProfile p = current();
        if (p == null) {
            p = new CompanyProfile();
            p.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        }
        p.setYearsInBusiness(req.yearsInBusiness());
        p.setTurnover(new ArrayList<>(turnover));
        p.setSimilarProjects(new ArrayList<>(req.similarProjects() == null ? List.of() : req.similarProjects()));
        p.setEquipment(new ArrayList<>(req.equipment() == null ? List.of() : req.equipment()));
        p.setPersonnel(new ArrayList<>(req.personnel() == null ? List.of() : req.personnel()));
        p.setUpdatedBy(userContextService.getCurrentUserId());
        CompanyProfile saved = profiles.saveAndFlush(p);
        return CompanyProfileDto.of(saved, uploadedEvidence().stream().map(CompanyEvidenceDto::of).toList());
    }

    /**
     * Declares an evidence file and returns a PUT for a key composed here, outside every package. The
     * file is not shown until {@link #registerEvidence} finds it in storage.
     */
    @Transactional
    public EvidenceUploadDto presignEvidence(PresignEvidenceRequest req) {
        String fileName = IngestDocumentService.cleanFileName(req.fileName());
        String lower = fileName.toLowerCase(Locale.ROOT);
        String extension = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
        if (!EVIDENCE_TYPES.contains(extension)) {
            throw new InvalidRequestException(fileName + " is not a type kept as evidence. Upload PDF, JPG, PNG, WEBP, "
                    + "XLSX, XLS or DOCX.");
        }
        if (req.fileSize() > limits.maxFileBytes()) {
            throw new InvalidRequestException(fileName + " is over the " + limits.maxFileBytes() / (1024 * 1024)
                    + " MB limit for a file.");
        }
        if (evidence.countAll() >= MAX_EVIDENCE) {
            throw new InvalidRequestException("The company profile holds at most " + MAX_EVIDENCE + " evidence files. "
                    + "Remove one first.");
        }
        CompanyEvidence e = new CompanyEvidence();
        e.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        e.setSection(req.section());
        e.setLabel(req.label() == null || req.label().isBlank() ? null : req.label().strip());
        e.setFileName(fileName);
        e.setContentType(req.contentType() == null || req.contentType().isBlank()
                ? DEFAULT_CONTENT_TYPE : req.contentType().trim());
        e.setSizeBytes(req.fileSize());
        e.setCreatedBy(userContextService.getCurrentUserId());
        // The key needs the id, which is assigned at persist.
        e.setSourceKey("pending");
        e = evidence.saveAndFlush(e);
        e.setSourceKey(DocumentIngestionStorageLayout.evidenceKey(e.getOrganization().getId(), e.getId()));
        evidence.save(e);
        PresignedUpload upload = fileStorageService.generateUploadUrlForKey(e.getSourceKey(), e.getContentType(),
                UPLOAD_URL_EXPIRY);
        return new EvidenceUploadDto(e.getId(), upload);
    }

    @Transactional
    public CompanyEvidenceDto registerEvidence(UUID evidenceId, RegisterDocumentRequest req) {
        CompanyEvidence e = requireEvidence(evidenceId);
        if (!e.getSourceKey().equals(req.key())) {
            throw new InvalidRequestException("That key was not issued for this evidence file");
        }
        if (e.isUploaded()) {
            throw new InvalidRequestException(e.getFileName() + " is already registered");
        }
        if (!fileStorageService.objectExists(e.getSourceKey())) {
            throw new InvalidRequestException("No uploaded file was found for " + e.getFileName()
                    + "; the upload may not have completed");
        }
        e.setAttachmentId(attachmentService.registerUploads(
                List.of(new RegisterUploadRequest(e.getSourceKey(), e.getFileName(), e.getContentType(), e.getSizeBytes())),
                DocumentIngestionStorageLayout.evidenceOwnerOf(e.getId()),
                DocumentIngestionStorageLayout.ATTACHMENT_FOLDER).getFirst().getId());
        return CompanyEvidenceDto.of(evidence.save(e));
    }

    @Transactional
    public void deleteEvidence(UUID evidenceId) {
        CompanyEvidence e = requireEvidence(evidenceId);
        if (e.isUploaded()) {
            attachmentService.deleteAttachmentOf(DocumentIngestionStorageLayout.evidenceOwnerOf(e.getId()),
                    e.getAttachmentId());
        }
        evidence.delete(e);
    }

    @Transactional(readOnly = true)
    public DownloadUrlDto evidenceUrl(UUID evidenceId) {
        CompanyEvidence e = requireEvidence(evidenceId);
        if (!e.isUploaded()) {
            throw new InvalidRequestException(e.getFileName() + " has not been uploaded yet");
        }
        return new DownloadUrlDto(fileStorageService.generateDownloadUrl(e.getSourceKey(), DOWNLOAD_URL_EXPIRY),
                DOWNLOAD_URL_EXPIRY.toSeconds());
    }

    @Transactional(readOnly = true)
    public List<QualificationRequirementEntryDto> requirements(UUID packageId) {
        requirePackage(packageId);
        return sorted(requirements.findByPackage(packageId, PageRequest.of(0, MAX_REQUIREMENTS))).stream()
                .map(QualificationRequirementEntryDto::of).toList();
    }

    /**
     * Records a person's status on an equipment or personnel requirement, or clears it with a null
     * status. Refused on a requirement the matrix compares from figures.
     */
    @Transactional
    public QualificationRequirementEntryDto setStatus(UUID requirementId, RequirementStatusRequest req) {
        QualificationRequirementEntry q = requirements.findByIdScoped(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("No qualification requirement " + requirementId));
        QualificationRequirementType type = QualificationRequirementType.valueOf(q.getRequirement());
        if (type.numeric()) {
            throw new InvalidRequestException(type.label() + " is compared with the company profile from its figures, "
                    + "so its status is not set by hand. Correct the profile or the requirement instead.");
        }
        IngestPackage p = requirePackage(q.getPackageId());
        if (p.getStage() != PackageStage.TENDER) {
            throw new InvalidRequestException(HIDDEN_NOTICE);
        }
        q.setPersonStatus(req.status());
        q.setPersonNote(req.note() == null || req.note().isBlank() ? null : req.note().strip());
        q.setStatusSetBy(userContextService.getCurrentUserId());
        q.setStatusSetAt(LocalDateTime.now());
        return QualificationRequirementEntryDto.of(requirements.save(q));
    }

    @Transactional(readOnly = true)
    public ComplianceMatrixDto matrix(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        if (p.getStage() != PackageStage.TENDER) {
            return new ComplianceMatrixDto(packageId, p.getStage(), false, HIDDEN_NOTICE, List.of());
        }
        CompanyProfile profile = current();
        Map<EvidenceSection, List<ComplianceMatrixDto.ComplianceMatrixEvidence>> bySection = new EnumMap<>(EvidenceSection.class);
        for (CompanyEvidence e : uploadedEvidence()) {
            bySection.computeIfAbsent(e.getSection(), s -> new ArrayList<>())
                    .add(new ComplianceMatrixDto.ComplianceMatrixEvidence(e.getId(), e.getLabel(), e.getFileName()));
        }
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        List<ComplianceMatrixDto.ComplianceMatrixRow> rows = new ArrayList<>();
        for (QualificationRequirementEntry q : sorted(requirements.findByPackage(packageId,
                PageRequest.of(0, MAX_REQUIREMENTS)))) {
            QualificationRequirementType type = QualificationRequirementType.valueOf(q.getRequirement());
            QualificationComparison.Outcome outcome = QualificationComparison.compare(type, q.getValueText(),
                    q.getMinValueText(), q.getPeriodText(), profile, q.getPersonStatus(), year);
            boolean byPerson = outcome.basis() == QualificationComparison.Basis.SET_BY_PERSON;
            rows.add(new ComplianceMatrixDto.ComplianceMatrixRow(q.getId(), type.name(), type.label(),
                    QualificationComparison.tenderValue(type, q.getValueText(), q.getMinValueText(), q.getPeriodText()),
                    outcome.companyValue(), outcome.status(), outcome.basis(), outcome.reason(),
                    byPerson ? q.getStatusSetBy() : null, byPerson ? q.getStatusSetAt() : null,
                    byPerson ? q.getPersonNote() : null,
                    bySection.getOrDefault(EvidenceSection.valueOf(type.name()), List.of()), q.getCandidateId(),
                    q.getDocumentId()));
        }
        return new ComplianceMatrixDto(packageId, p.getStage(), true, NOTICE, rows);
    }

    /** In the order the stub lists them: turnover, similar projects, experience, equipment, personnel. */
    private static List<QualificationRequirementEntry> sorted(List<QualificationRequirementEntry> rows) {
        return rows.stream().sorted(Comparator
                        .comparingInt((QualificationRequirementEntry q) ->
                                QualificationRequirementType.valueOf(q.getRequirement()).ordinal())
                        .thenComparing(QualificationRequirementEntry::getCreatedAt)
                        .thenComparing(QualificationRequirementEntry::getId))
                .toList();
    }

    private CompanyProfile current() {
        return profiles.findCurrent(PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    private List<CompanyEvidence> uploadedEvidence() {
        return evidence.findUploaded(PageRequest.of(0, MAX_EVIDENCE));
    }

    private CompanyEvidence requireEvidence(UUID evidenceId) {
        return evidence.findByIdScoped(evidenceId)
                .orElseThrow(() -> new ResourceNotFoundException("No evidence file " + evidenceId));
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }
}
