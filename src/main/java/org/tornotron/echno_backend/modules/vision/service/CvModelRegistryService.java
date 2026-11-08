package org.tornotron.echno_backend.modules.vision.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.VisionProperties;
import org.tornotron.echno_backend.modules.vision.domain.CvModel;
import org.tornotron.echno_backend.modules.vision.domain.CvModelClass;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.dto.ChangeCvModelVersionStatusRequest;
import org.tornotron.echno_backend.modules.vision.dto.CreateCvModelRequest;
import org.tornotron.echno_backend.modules.vision.dto.CvModelClassDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelVersionDto;
import org.tornotron.echno_backend.modules.vision.dto.RegisterCvModelVersionRequest;
import org.tornotron.echno_backend.modules.vision.repository.CvModelRepository;
import org.tornotron.echno_backend.modules.vision.repository.CvModelVersionRepository;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The model registry: families, their versions, and the one decision on a version, activating it.
 *
 * <p>Activation is a person's decision after reading the report, so nothing here promotes a version
 * on its own, and it refuses a version whose licence is off the allowlist or that has no metrics and
 * report to read. Activating retires the previous active version in the same transaction; the old
 * row goes first because the partial unique index allows one active row per model at every statement.
 */
@Slf4j
@Service
@VisionModuleEnabled
@RequiredArgsConstructor
public class CvModelRegistryService {

    /** A catalogue, not a growing record; this is the ceiling on the list read. */
    static final int MAX_MODELS = 200;

    private final CvModelRepository models;
    private final CvModelVersionRepository versions;
    private final CvClassTaxonomy taxonomy;
    private final VisionProperties properties;
    private final UserContextService userContextService;

    @Transactional(readOnly = true)
    public List<CvModelDto> listModels() {
        List<CvModel> rows = models.findPage(PageRequest.of(0, MAX_MODELS));
        Map<UUID, List<CvModelVersionDto>> byModel = new HashMap<>();
        Map<UUID, String> keys = new HashMap<>();
        rows.forEach(m -> keys.put(m.getId(), m.getModelKey()));
        if (!rows.isEmpty()) {
            for (CvModelVersion v : versions.findByModels(keys.keySet())) {
                byModel.computeIfAbsent(v.getModelId(), id -> new ArrayList<>()).add(toDto(v, keys.get(v.getModelId())));
            }
        }
        return rows.stream().map(m -> toDto(m, byModel.getOrDefault(m.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public CvModelVersionDto getVersion(String modelKey, String version) {
        CvModel model = requireModel(modelKey);
        return toDto(requireVersion(model, version), model.getModelKey());
    }

    @Transactional
    public CvModelDto createModel(CreateCvModelRequest req) {
        if (models.findByModelKey(req.key()).isPresent()) {
            throw new CvModelConflictException("A model with key '" + req.key() + "' already exists");
        }
        CvModel model = new CvModel();
        model.setModelKey(req.key());
        model.setName(req.name());
        model.setTask(req.task());
        model.setFamily(req.family());
        CvModel saved = models.saveAndFlush(model);
        log.info("Model family {} created", saved.getModelKey());
        return toDto(saved, List.of());
    }

    @Transactional
    public CvModelVersionDto registerVersion(String modelKey, RegisterCvModelVersionRequest req) {
        CvModel model = requireModel(modelKey);
        if (versions.findByModelAndVersion(model.getId(), req.version()).isPresent()) {
            throw new CvModelConflictException("Version " + req.version() + " of " + modelKey + " already exists");
        }
        CvModelVersion row = new CvModelVersion();
        row.setModelId(model.getId());
        row.setVersion(req.version());
        row.setStatus(CvModelVersionStatus.DRAFT);
        row.setClasses(validatedClasses(model, req.classes()));
        row.setLicence(req.licence());
        row.setBaseWeights(req.baseWeights());
        row.setWeightsUri(req.weightsUri());
        row.setWeightsSha256(req.weightsSha256());
        row.setRuntime(req.runtime());
        row.setInputSize(req.inputSize());
        row.setTrainingRelease(req.trainingRelease());
        row.setTrainingRunUri(req.trainingRunUri());
        row.setMetrics(req.metrics());
        row.setEvalReportUri(req.evalReportUri());
        row.setRegisteredById(userContextService.getCurrentUserId());
        CvModelVersion saved = versions.saveAndFlush(row);
        log.info("Model version {} {} registered as DRAFT", modelKey, saved.getVersion());
        return toDto(saved, modelKey);
    }

    /**
     * @throws InvalidRequestException   on a target other than ACTIVE or RETIRED, or an activation the
     *                                   version does not qualify for
     * @throws CvModelConflictException  on a version that is already retired or already active
     */
    @Transactional
    public CvModelVersionDto changeStatus(String modelKey, String version, ChangeCvModelVersionStatusRequest req) {
        // Locks the family row, so two activations of one model run one after the other.
        CvModel model = models.findByModelKeyForUpdate(modelKey)
                .orElseThrow(() -> new ResourceNotFoundException("No model with key '" + modelKey + "'"));
        CvModelVersion row = requireVersion(model, version);
        if (row.getStatus() == CvModelVersionStatus.RETIRED) {
            throw new CvModelConflictException("Version " + version + " of " + modelKey
                    + " is retired, and retired is terminal; register a new version");
        }
        LocalDateTime now = LocalDateTime.now();
        switch (req.status()) {
            case ACTIVE -> activate(model, row, now);
            case RETIRED -> retire(row, now);
            default -> throw new InvalidRequestException("A version can be moved to ACTIVE or RETIRED only");
        }
        CvModelVersion saved = versions.saveAndFlush(row);
        log.info("Model version {} {} moved to {} by user {}: {}", modelKey, version, saved.getStatus(),
                userContextService.getCurrentUserId(), req.note());
        return toDto(saved, modelKey);
    }

    private void activate(CvModel model, CvModelVersion row, LocalDateTime now) {
        if (row.getStatus() == CvModelVersionStatus.ACTIVE) {
            throw new CvModelConflictException("Version " + row.getVersion() + " is already active");
        }
        if (!properties.getAllowedLicences().contains(row.getLicence())) {
            throw new InvalidRequestException("Licence " + row.getLicence() + " is not on echno.vision.allowed-licences "
                    + properties.getAllowedLicences());
        }
        if (row.getMetrics() == null || row.getMetrics().isEmpty()) {
            throw new InvalidRequestException("A version needs metrics before it can be activated");
        }
        if (row.getEvalReportUri() == null || row.getEvalReportUri().isBlank()) {
            throw new InvalidRequestException("A version needs an evaluation report before it can be activated");
        }
        for (CvModelVersion active : versions.findByModelAndStatus(model.getId(), CvModelVersionStatus.ACTIVE)) {
            retire(active, now);
            versions.saveAndFlush(active);
        }
        row.setStatus(CvModelVersionStatus.ACTIVE);
        row.setActivatedAt(now);
    }

    private static void retire(CvModelVersion row, LocalDateTime now) {
        row.setStatus(CvModelVersionStatus.RETIRED);
        row.setRetiredAt(now);
    }

    /** A version's classes are a subset of the taxonomy, each with its shape, and fit the family's task. */
    private List<CvModelClass> validatedClasses(CvModel model, List<CvModelClassDto> classes) {
        String shape = model.getTask() == CvModelTask.SEGMENT ? CvClassTaxonomy.POLYGON : CvClassTaxonomy.BOX;
        Set<String> seen = new HashSet<>();
        List<CvModelClass> validated = new ArrayList<>(classes.size());
        for (CvModelClassDto c : classes) {
            String expected = taxonomy.shapeOf(c.key()).orElseThrow(() ->
                    new InvalidRequestException("Class '" + c.key() + "' is not in the taxonomy"));
            if (!expected.equals(c.shape())) {
                throw new InvalidRequestException("Class '" + c.key() + "' is drawn as " + expected + ", not " + c.shape());
            }
            if (!shape.equals(c.shape())) {
                throw new InvalidRequestException("A " + model.getTask() + " model outputs " + shape
                        + " classes; '" + c.key() + "' is " + c.shape());
            }
            if (!seen.add(c.key())) {
                throw new InvalidRequestException("Class '" + c.key() + "' is listed twice");
            }
            validated.add(new CvModelClass(c.key(), c.shape(), c.threshold()));
        }
        return validated;
    }

    private CvModel requireModel(String modelKey) {
        return models.findByModelKey(modelKey)
                .orElseThrow(() -> new ResourceNotFoundException("No model with key '" + modelKey + "'"));
    }

    private CvModelVersion requireVersion(CvModel model, String version) {
        return versions.findByModelAndVersion(model.getId(), version)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No version " + version + " of model '" + model.getModelKey() + "'"));
    }

    private static CvModelDto toDto(CvModel m, List<CvModelVersionDto> versions) {
        return new CvModelDto(m.getModelKey(), m.getName(), m.getTask(), m.getFamily(), versions);
    }

    private static CvModelVersionDto toDto(CvModelVersion v, String modelKey) {
        return new CvModelVersionDto(v.getId(), modelKey, v.getVersion(), v.getStatus(),
                v.getClasses().stream().map(c -> new CvModelClassDto(c.key(), c.shape(), c.threshold())).toList(),
                v.getLicence(), v.getBaseWeights(), v.getWeightsUri(), v.getWeightsSha256(), v.getRuntime(),
                v.getInputSize(), v.getTrainingRelease(), v.getTrainingRunUri(), v.getMetrics(), v.getEvalReportUri(),
                v.getRegisteredById(), v.getActivatedAt(), v.getRetiredAt(), v.getCreatedAt(), v.getUpdatedAt());
    }
}
