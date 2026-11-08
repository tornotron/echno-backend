package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqTargetRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.CreatePackageRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.PackageDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.modules.documentingestion.dto.TenderProfileDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.TenderProfileRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/** Packages as the API sees them. Every read is scoped, so another tenant's package reads as absent. */
@Service
@RequiredArgsConstructor
public class IngestPackageService {

    static final int MAX_PAGE_SIZE = 100;

    private final IngestPackageRepository packages;
    private final IngestDocumentRepository documents;
    private final ProjectRepository projects;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final CandidateRepository candidates;
    private final ContractBoqPort contractBoq;
    private final TenderProfileRepository profiles;

    @Transactional
    public PackageDto create(CreatePackageRequest req) {
        if (req.projectId() != null
                && !projects.existsByIdAndOrganization_Id(req.projectId(), TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("No project " + req.projectId() + " in this organization");
        }
        IngestPackage p = new IngestPackage();
        p.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        p.setName(req.name().trim());
        p.setProjectId(req.projectId());
        p.setStage(req.stage() == null ? PackageStage.TENDER : req.stage());
        p.setCreatedBy(userContextService.getCurrentUserId());
        p.setUpdatedBy(p.getCreatedBy());
        return PackageDto.of(packages.save(p), 0);
    }

    @Transactional(readOnly = true)
    public Page<PackageDto> list(int page, int size) {
        return packages.findPage(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)))
                .map(p -> PackageDto.of(p, documents.countInPackage(p.getId())));
    }

    @Transactional(readOnly = true)
    public PackageDto get(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        return PackageDto.of(p, documents.countInPackage(p.getId()));
    }

    /** The package's tender profile, or 404 while no project field has been applied to it. */
    @Transactional(readOnly = true)
    public TenderProfileDto tenderProfile(UUID packageId) {
        requirePackage(packageId);
        return profiles.findByPackage(packageId).map(TenderProfileDto::of)
                .orElseThrow(() -> new ResourceNotFoundException("No tender facts have been applied to package " + packageId));
    }

    /**
     * Archives a package: it keeps its rows and its provenance and takes no more files. There is no
     * delete. Once a value is applied, provenance points at the package's sources for good.
     */
    @Transactional
    public PackageDto archive(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        p.setStatus(PackageStatus.ARCHIVED);
        p.setUpdatedBy(userContextService.getCurrentUserId());
        return PackageDto.of(packages.save(p), documents.countInPackage(p.getId()));
    }

    /**
     * Sets the sub-contract the package's BOQ is written to, or clears it. The contract must be in the
     * organization, and in the package's project when the package has one. Once BOQ rows are on a
     * contract, the target cannot move, or one tender BOQ would end up split across two contracts.
     */
    @Transactional
    public PackageDto setBoqTarget(UUID packageId, BoqTargetRequest req) {
        IngestPackage p = requireOpenPackage(packageId);
        Long target = req.subContractId();
        if (target != null) {
            ContractBoqPort.ContractRef contract = contractBoq.findContract(target)
                    .orElseThrow(() -> new ResourceNotFoundException("No sub-contract " + target + " in this organization"));
            if (p.getProjectId() != null && !p.getProjectId().equals(contract.projectId())) {
                throw new InvalidRequestException("Sub-contract " + contract.name() + " belongs to another project");
            }
        }
        boolean moving = p.getSubContractId() != null && !p.getSubContractId().equals(target);
        if (moving && candidates.countByStatus(packageId, CandidateKind.BOQ_ITEM, CandidateStatus.APPLIED) > 0) {
            throw new InvalidRequestException("BOQ rows of this package are already on a contract, so the target cannot change");
        }
        p.setSubContractId(target);
        p.setUpdatedBy(userContextService.getCurrentUserId());
        return PackageDto.of(packages.save(p), documents.countInPackage(p.getId()));
    }

    IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    IngestPackage requireOpenPackage(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        if (p.getStatus() != PackageStatus.OPEN) {
            throw new InvalidRequestException("The package is archived and takes no more files");
        }
        return p;
    }
}
