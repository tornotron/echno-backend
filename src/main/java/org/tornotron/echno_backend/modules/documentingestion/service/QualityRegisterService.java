package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualityRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.SourceRefDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.QualityRequirementEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.project.ProjectRepository;

/**
 * Reads of the inspection and test plan read from specifications (design section 4.4): by package, and
 * by project across its packages, each row with the citations behind its values.
 */
@Service
@RequiredArgsConstructor
public class QualityRegisterService {

    static final int MAX_PAGE_SIZE = 200;

    private final IngestPackageRepository packages;
    private final QualityRequirementEntryRepository entries;
    private final SourceRefRepository sources;
    private final ProjectRepository projects;

    @Transactional(readOnly = true)
    public Page<QualityRequirementEntryDto> forPackage(UUID packageId, int page, int size) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
        return withCitations(entries.findByPackage(packageId, pageOf(page, size)));
    }

    @Transactional(readOnly = true)
    public Page<QualityRequirementEntryDto> forProject(Long projectId, int page, int size) {
        if (!projects.existsByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("No project " + projectId + " in this organization");
        }
        return withCitations(entries.findByProject(projectId, pageOf(page, size)));
    }

    private Page<QualityRequirementEntryDto> withCitations(Page<QualityRequirementEntry> rows) {
        List<UUID> candidateIds = rows.map(QualityRequirementEntry::getCandidateId).getContent();
        Map<UUID, List<SourceRefDto>> refs = candidateIds.isEmpty() ? Map.of()
                : sources.findForCandidates(candidateIds).stream()
                        .collect(Collectors.groupingBy(s -> s.getCandidateId(), LinkedHashMap::new,
                                Collectors.mapping(SourceRefDto::of, Collectors.toList())));
        return rows.map(q -> QualityRequirementEntryDto.of(q, refs.getOrDefault(q.getCandidateId(), List.of())));
    }

    private static PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
