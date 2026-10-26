package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.inspections.DefectAnnotationShape;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.repositories.DefectPhotoAnnotationRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manifest contract with the {@code echno-datasets} tooling (#893).
 *
 * <p>The export runs through {@link DatasetExportService} against fixed rows (one image from each
 * source) and the manifest it writes is compared byte for byte with
 * {@code src/test/resources/dataset/manifest.golden.jsonl}. echno-datasets vendors that same
 * file under {@code tests/fixtures/} and asserts its validator and anonymiser accept every line,
 * so a change to the line shows up here first and has to be carried over to the tooling.
 *
 * <p>The run key is the one value the export makes up (a timestamp and a random suffix); it is
 * replaced with {@link #GOLDEN_RUN_KEY} before the comparison. Everything else is fixed.
 *
 * <p>To regenerate after a deliberate change:
 * <pre>ECHNO_UPDATE_GOLDEN=1 ./gradlew test --tests '*DatasetManifestContractTest'</pre>
 * then copy the file to {@code tests/fixtures/manifest.golden.jsonl} in echno-datasets.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetManifestContractTest {

    static final Path GOLDEN = Path.of("src/test/resources/dataset/manifest.golden.jsonl");
    static final String GOLDEN_RUN_KEY = "run-20261026T000000Z-0a1b2c3d";

    /** The tooling's {@code EVIDENCE_ID_RE} ({@code echno_datasets/manifest.py}). */
    private static final Pattern TOOLING_EVIDENCE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private static final Long ORG = 7L;
    private static final UUID INSPECTION_ID = UUID.fromString("3f2c1a9e-5b7d-4c11-9e2a-1d6b8f0c4a01");
    private static final UUID OBSERVATION_ID = UUID.fromString("b8e4d2c6-0a1f-4e3b-8c7d-2f9a6e5b1c02");
    private static final UUID NODE_ID = UUID.fromString("c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e03");
    private static final String DEFECT_PHOTO_REF =
            "https://cdn.echno.example/construction/inspection/2026/09/defect-1.jpg";

    @Mock private OrganizationRepository organizationRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private InspectionRepository inspectionRepository;
    @Mock private DefectPhotoAnnotationRepository annotationRepository;
    @Mock private ObservationRepository observationRepository;
    @Mock private SpatialNodeService spatialNodeService;
    @Mock private FileStorageService fileStorageService;
    @Mock private DatasetExportRunRepository runRepository;
    @Mock private DatasetExportedItemRepository itemRepository;

    // The mapper Spring Boot builds for the application context starts from this builder.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final Map<UUID, DatasetExportRun> savedRuns = new ConcurrentHashMap<>();
    private DatasetExportService service;

    @BeforeEach
    void setUp() {
        service = new DatasetExportService(organizationRepository, attachmentRepository, inspectionRepository,
                annotationRepository, observationRepository, spatialNodeService, fileStorageService,
                runRepository, itemRepository, new DatasetExportProperties(), objectMapper,
                new TransactionalWorkRunner());

        Organization org = new Organization();
        org.setId(ORG);
        org.setDatasetConsent(true);
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org));
        when(organizationRepository.getReferenceById(ORG)).thenReturn(org);
        when(runRepository.save(any(DatasetExportRun.class))).thenAnswer(inv -> {
            DatasetExportRun run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            savedRuns.put(run.getId(), run);
            return run;
        });
        when(runRepository.findByIdAndOrganization_Id(any(UUID.class), eq(ORG)))
                .thenAnswer(inv -> Optional.ofNullable(savedRuns.get(inv.<UUID>getArgument(0))));
        when(runRepository.findByOrganization_IdAndStatus(ORG, DatasetExportRunStatus.RUNNING)).thenReturn(List.of());
        when(itemRepository.save(any(DatasetExportedItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findSourceRefs(eq(ORG), any())).thenReturn(List.of());

        Inspection inspection = new Inspection();
        inspection.setId(INSPECTION_ID);
        inspection.setOrganization(org);
        inspection.setProjectId(42L);
        inspection.setType(InspectionType.QUALITY);
        inspection.setCategory(InspectionCategory.QA_QC);
        inspection.setSpatialNodeId(NODE_ID);
        inspection.setActualStartTime(LocalDateTime.of(2026, 9, 1, 9, 30));
        inspection.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        OrgTrade trade = new OrgTrade();
        trade.setCode("concrete");
        trade.setName("Concrete");
        inspection.setTradeRef(trade);
        when(inspectionRepository.findAllById(anyList())).thenReturn(List.of(inspection));

        when(attachmentRepository.findByEntityTypeAndOrganization_IdOrderByIdAsc("INSPECTION_EVIDENCE", ORG))
                .thenReturn(List.of(attachment(11L, "inspection/2026/09/slab-crack.jpg", "image/jpeg", 123456L,
                        INSPECTION_ID)));

        InspectionDefect defect = new InspectionDefect();
        defect.setInspection(inspection);
        defect.setPhotos(new ArrayList<>(List.of(DEFECT_PHOTO_REF)));
        when(inspectionRepository.findDefectsForOrganization(ORG)).thenReturn(List.of(defect));
        when(fileStorageService.keyForStoredReference(DEFECT_PHOTO_REF))
                .thenReturn(Optional.of("inspection/2026/09/defect-1.jpg"));
        when(annotationRepository.findByOrganization_IdOrderByPhotoAscLineOrderAsc(ORG)).thenReturn(List.of(
                mark(DEFECT_PHOTO_REF, "0.10", "0.20", "0.30", "0.40", "crack", 0),
                mark(DEFECT_PHOTO_REF, "0.50", "0.50", "0.90", "0.95", "honeycombing", 1)));

        Observation observation = new Observation();
        observation.setId(OBSERVATION_ID);
        observation.setOrganization(org);
        observation.setProjectId(42L);
        observation.setInspectionId(INSPECTION_ID);
        observation.setSource(ObservationSource.ROBOT);
        observation.setSourceDeviceId("rover-01");
        observation.setObservedAt(LocalDateTime.of(2026, 9, 2, 14, 5, 6));
        when(attachmentRepository.findByEntityTypeAndOrganization_IdOrderByIdAsc("OBSERVATION_EVIDENCE", ORG))
                .thenReturn(List.of(attachment(13L, "observation/2026/09/rover-frame.png", "image/png", 555L,
                        OBSERVATION_ID)));
        when(observationRepository.findAllById(anyList())).thenReturn(List.of(observation));

        when(spatialNodeService.pathsOf(anyCollection())).thenReturn(Map.of(NODE_ID, List.of(
                new SpatialPathSegment(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), SpatialLevel.BUILDING,
                        "B1", "Tower B"),
                new SpatialPathSegment(UUID.fromString("00000000-0000-4000-8000-0000000000f4"), SpatialLevel.FLOOR,
                        "F4", "Fourth floor"),
                new SpatialPathSegment(NODE_ID, SpatialLevel.ZONE, null, "Zone B"))));
    }

    @Test
    void theManifestMatchesTheGoldenFileTheToolingVendors() throws Exception {
        String actual = exportManifest();
        if (System.getenv("ECHNO_UPDATE_GOLDEN") != null) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        assertThat(GOLDEN).as("golden file; regenerate with ECHNO_UPDATE_GOLDEN=1").exists();
        assertThat(actual).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
    }

    @Test
    void everyLineCarriesConsentForItsOwnOrganization() throws Exception {
        for (JsonNode line : lines(exportManifest())) {
            assertThat(line.has("consent")).as("consent field on %s", line.get("source")).isTrue();
            assertThat(line.get("consent").isBoolean()).isTrue();
            assertThat(line.get("consent").booleanValue()).isTrue();
            assertThat(line.get("consent_org_id").asLong()).isEqualTo(line.get("org_id").asLong());
            assertThat(line.get("licence").asText()).isEqualTo("org-consent");
        }
    }

    @Test
    void everyEvidenceIdMatchesTheToolingPatternAndIsUnique() throws Exception {
        List<String> ids = lines(exportManifest()).stream().map(l -> l.get("evidence_id").asText()).toList();
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).matches(TOOLING_EVIDENCE_ID));
    }

    private String exportManifest() {
        DatasetExportRunDto run = service.runForOrganization(ORG, "user:1");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).putObject(eq("echno-datasets"), eq(run.manifestKey()), body.capture(),
                eq("application/x-ndjson"));
        return new String(body.getValue(), StandardCharsets.UTF_8).replace(run.runKey(), GOLDEN_RUN_KEY);
    }

    private List<JsonNode> lines(String manifest) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : manifest.split("\n")) {
            if (!line.isBlank()) {
                out.add(objectMapper.readTree(line));
            }
        }
        return out;
    }

    private static Attachment attachment(Long id, String key, String contentType, Long size, UUID owner) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setStorageKey(key);
        a.setContentType(contentType);
        a.setFileSize(size);
        a.setEntityUuid(owner);
        a.setCreatedAt(LocalDateTime.of(2026, 8, 31, 10, 0));
        return a;
    }

    private static DefectPhotoAnnotation mark(String photo, String x1, String y1, String x2, String y2, String label,
                                              int order) {
        DefectPhotoAnnotation m = new DefectPhotoAnnotation();
        m.setId(UUID.nameUUIDFromBytes((photo + order).getBytes(StandardCharsets.UTF_8)));
        m.setInspectionId(INSPECTION_ID);
        m.setPhoto(photo);
        m.setShape(DefectAnnotationShape.RECTANGLE);
        m.setX1(new BigDecimal(x1));
        m.setY1(new BigDecimal(y1));
        m.setX2(new BigDecimal(x2));
        m.setY2(new BigDecimal(y2));
        m.setLabel(label);
        m.setLineOrder(order);
        return m;
    }
}
