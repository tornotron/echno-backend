package org.tornotron.echno_backend.modules.documentingestion.review;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.SourceRefDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.ProvenanceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;

/** Where each field of a live record came from, read by target. Scoped like every other read. */
@Service
@RequiredArgsConstructor
public class ProvenanceService {

    static final int MAX_ROWS = 500;

    private final ProvenanceRepository provenance;
    private final SourceRefRepository sources;

    @Transactional(readOnly = true)
    public List<ProvenanceDto> forTarget(String targetType, String targetId) {
        List<Provenance> rows = provenance.findForTarget(targetType, targetId, PageRequest.of(0, MAX_ROWS));
        List<UUID> candidateIds = rows.stream().map(Provenance::getCandidateId).distinct().toList();
        Map<UUID, SourceRef> refs = candidateIds.isEmpty() ? Map.of()
                : sources.findForCandidates(candidateIds).stream()
                        .collect(Collectors.toMap(SourceRef::getId, Function.identity()));
        return rows.stream()
                .map(p -> ProvenanceDto.of(p, p.getSourceRefId() == null ? null
                        : refs.containsKey(p.getSourceRefId()) ? SourceRefDto.of(refs.get(p.getSourceRefId())) : null))
                .filter(Objects::nonNull)
                .toList();
    }
}
