package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.num;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.apply.BoqItemApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheetStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqMappingRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqSheetDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqTargetRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision.Action;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto.Outcome;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewAccess;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.BoqSheetService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBoqPortAdapter;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 7 of the build plan on the real migration, from a parsed workbook to a sub-contract's BOQ:
 * the worker's outputs are served from a mocked object store in the contract's shape, and everything
 * after that is real, including the Work Progress BOQ service behind its port. Every transaction is
 * committed as in a request, so a failed row really rolls back, and the tenant filter is wired as in
 * the application, so another tenant's rows read as absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({BoqSheetService.class, IngestPackageService.class, ParsedOutputReader.class, CandidateWriter.class,
        ReviewService.class, ApplyService.class, ApplyGuard.class, BoqItemApplier.class, ProvenanceService.class,
        ContractBoqPortAdapter.class, ContractBillingService.class, BillingSupport.class,
        TransactionalWorkRunner.class, TransactionRetryTemplate.class, SimpleMeterRegistry.class,
        UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class BoqIngestionIT extends AbstractIntegrationTest {

    static final String[] HEADER = {"Item", "Description", "Unit", "Qty", "Rate", "Amount"};
    static final List<String> LIVE_TABLES = List.of("project", "wbs_element", "contract_boq_item", "contract_milestone");

    @Autowired
    BoqSheetService sheets;

    @Autowired
    IngestPackageService packages;

    @Autowired
    ReviewService review;

    @Autowired
    ApplyService apply;

    @Autowired
    ProvenanceService provenance;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ProjectRepository projects;

    @Autowired
    SubContractRepository subContracts;

    @Autowired
    JdbcTemplate jdbc;

    // Which candidates the caller may decide, which the access matrix answers per kind.
    @MockitoBean
    ReviewAccess reviewAccess;

    // The BOQ endpoint's guard, which applying a BOQ row asks through ApplyGuard.
    @MockitoBean(name = "access")
    AccessGuard access;

    @MockitoBean
    FileStorageService storage;

    @MockitoBean
    ModuleRegistry modules;

    Long orgId;
    Long otherOrgId;
    Long projectId;
    Long otherProjectId;
    Long contractId;
    Long otherProjectContractId;
    Long otherOrgContractId;
    UUID packageId;
    UUID documentId;

    @BeforeEach
    void aPackageWithAParsedWorkbook() {
        orgId = organization("BOQ ");
        otherOrgId = organization("Other ");
        projectId = project(orgId, "Riverside Tower");
        otherProjectId = project(orgId, "Hillside Tower");
        contractId = contract(orgId, projectId, "Civil works");
        otherProjectContractId = contract(orgId, otherProjectId, "Hillside civil works");
        otherOrgContractId = contract(otherOrgId, project(otherOrgId, "Elsewhere"), "Other civil works");
        packageId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, project_id, stage, status, created_at, "
                + "updated_at) VALUES (?, ?, 'Tender', ?, 'TENDER', 'OPEN', now(), now())", packageId, orgId, projectId);
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                        + "file_name, file_type, size_bytes, status, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, "
                        + "'BOQ.xlsx', 'XLSX', 1, 'PARSED', now(), now())",
                documentId, orgId, packageId, prefix(), "a".repeat(64));
        TenantContext.setCurrentOrgId(orgId);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        when(reviewAccess.mayReview(any())).thenReturn(true);
        when(access.can("progress-bills", "edit")).thenReturn(true);
        when(modules.isEnabledForOrg(eq(ContractBoqPort.MODULE_ID), anyLong())).thenReturn(true);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (Long org : List.of(orgId, otherOrgId)) {
            jdbc.update("DELETE FROM doc_ingest_provenance WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_source_ref WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_candidate WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_boq_sheet WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_segment WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_document WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM doc_ingest_package WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM contract_boq_item WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM sub_contract WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM project WHERE organization_id = ?", org);
            jdbc.update("DELETE FROM organization WHERE id = ?", org);
        }
    }

    @Test
    void theTicketSampleGoesFromWorkbookToTheContractWithASourceCellOnEveryField() {
        serve(meta(sheet(0, "BOQ", false)), new BoqRows("BOQ", 0)
                .row(1, (Object[]) HEADER)
                .row(2, num("1.01"), "Excavation in ordinary soil", "m³", num("2500", "2,500"), num("350", "₹350"),
                        num("875000", "₹8,75,000"))
                .row(3, num("1.02"), "PCC 1:4:8", "m³", num("320"), num("6500", "₹6,500"), num("2080000", "₹20,80,000"))
                .row(4, num("1.03"), "RCC M25", "m³", num("850"), num("9800", "₹9,800"), num("8330000", "₹83,30,000"))
                .row(5, num("1.04"), "Reinforcement steel Fe500", "MT", num("105"), num("68000", "₹68,000"),
                        num("7140000", "₹71,40,000"))
                .jsonl());
        Map<String, Long> before = liveCounts();

        BoqSheetDto detected = sheets.detect(documentId).get(0);
        assertThat(detected.status()).isEqualTo(BoqSheetStatus.DETECTED);
        assertThat(detected.headerRow()).isEqualTo(1);
        assertThat(detected.preview()).hasSize(5);
        assertThat(sheets.detect(documentId)).as("detecting again adds nothing").hasSize(1);

        BoqSheetDto read = sheets.extract(documentId, detected.id(), mapping(detected));
        assertThat(read.status()).isEqualTo(BoqSheetStatus.EXTRACTED);
        assertThat(read.summary().items()).isEqualTo(4);
        List<CandidateDto> proposed = candidates(null);
        assertThat(proposed).hasSize(4).allSatisfy(c -> assertThat(c.flags()).isEmpty());

        packages.setBoqTarget(packageId, new BoqTargetRequest(contractId));
        assertThat(review.decide(packageId, accept(proposed))).extracting(DecisionOutcomeDto::outcome)
                .containsOnly(Outcome.ACCEPTED);
        assertThat(liveCounts()).as("nothing reaches a live table before apply").isEqualTo(before);

        assertThat(apply.apply(packageId, CandidateKind.BOQ_ITEM)).isEqualTo(new ApplyResultDto(4, 0, 0));

        List<Map<String, Object>> boq = jdbc.queryForList("SELECT id, item_code, unit, contract_quantity, rate, amount "
                + "FROM contract_boq_item WHERE sub_contract_id = ? ORDER BY sort_order", contractId);
        assertThat(boq).extracting(r -> r.get("item_code")).containsExactly("1.01", "1.02", "1.03", "1.04");
        assertThat((BigDecimal) boq.get(2).get("amount")).isEqualByComparingTo("8330000");
        List<ProvenanceDto> trail = provenance.forTarget(BoqItemApplier.TARGET_TYPE, boq.get(2).get("id").toString());
        assertThat(trail).extracting(ProvenanceDto::targetField)
                .containsExactlyInAnyOrder("itemCode", "description", "unit", "contractQuantity", "rate");
        ProvenanceDto quantity = trail.stream().filter(p -> p.targetField().equals("contractQuantity")).findFirst().orElseThrow();
        assertThat(quantity.valueAccepted()).isEqualTo("850");
        assertThat(quantity.source().sheet()).isEqualTo("BOQ");
        assertThat(quantity.source().rowNumber()).isEqualTo(4);
        assertThat(quantity.source().cell()).isEqualTo("D4");
    }

    @Test
    void twoThousandRowsApplyAcrossAFailureMidwayWithNoDuplicate() {
        BoqRows workbook = new BoqRows("BOQ", 0).row(1, (Object[]) HEADER);
        for (int i = 1; i <= 2000; i++) {
            workbook.row(i + 1, "C" + i, "Item " + i, "cum", num(String.valueOf(i)), num("10"), num(String.valueOf(i * 10)));
        }
        serve(meta(sheet(0, "BOQ", false)), workbook.jsonl());
        BoqSheetDto sheet = sheets.detect(documentId).get(0);
        assertThat(sheets.extract(documentId, sheet.id(), mapping(sheet)).summary().items()).isEqualTo(2000);
        packages.setBoqTarget(packageId, new BoqTargetRequest(contractId));

        List<CandidateDto> all = allCandidates();
        assertThat(all).hasSize(2000);
        CandidateDto broken = all.get(1234);
        List<CandidateDecision> decisions = new ArrayList<>();
        for (CandidateDto c : all) {
            decisions.add(c == broken
                    ? new CandidateDecision(c.id(), Action.EDIT, Map.of("quantity", "0"), null)
                    : new CandidateDecision(c.id(), Action.ACCEPT, null, null));
        }
        for (int from = 0; from < decisions.size(); from += 500) {
            review.decide(packageId, decisions.subList(from, Math.min(from + 500, decisions.size())));
        }

        ApplyResultDto first = apply.apply(packageId, CandidateKind.BOQ_ITEM);

        assertThat(first).isEqualTo(new ApplyResultDto(1999, 1, 0));
        CandidateDto failed = candidates(CandidateStatus.APPLY_FAILED).get(0);
        assertThat(failed.id()).isEqualTo(broken.id());
        assertThat(failed.applyError()).contains("contractQuantity");
        assertThat(apply.apply(packageId, CandidateKind.BOQ_ITEM)).as("applying again writes nothing twice")
                .isEqualTo(new ApplyResultDto(0, 0, 0));

        review.decide(packageId, List.of(new CandidateDecision(broken.id(), Action.EDIT, Map.of("quantity", "5"), null)));
        assertThat(apply.apply(packageId, CandidateKind.BOQ_ITEM)).isEqualTo(new ApplyResultDto(1, 0, 0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_boq_item WHERE sub_contract_id = ?", Long.class,
                contractId)).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT item_code) FROM contract_boq_item WHERE sub_contract_id = ?",
                Long.class, contractId)).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT contract_quantity FROM contract_boq_item WHERE sub_contract_id = ? AND "
                + "item_code = ?", BigDecimal.class, contractId, broken.payload().get("itemCode"))).isEqualByComparingTo("5");
        assertThatThrownBy(() -> sheets.extract(documentId, sheet.id(), mapping(sheet)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("already on a contract");
    }

    @Test
    void threeSheetsReadIntoOnePackageAndReadingASheetAgainReplacesItsRows() {
        BoqRows civil = new BoqRows("Civil", 0).row(1, (Object[]) HEADER)
                .row(2, "1", "Earthwork", "cum", num("100"), num("200"), num("20000"))
                .row(3, "2", "PCC", "cum", num("10"), num("5000"), num("50000"));
        BoqRows electrical = new BoqRows("Electrical", 1).row(1, "Schedule B")
                .row(2, "Sl. No.", "Description of Item", "Quantity", "Unit", "Rate (Rs.)", "Amount (Rs.)")
                .row(3, "1", "Wiring point", num("40"), "point", num("650"), num("26000"));
        BoqRows plumbing = new BoqRows("Plumbing", 2).row(1, (Object[]) HEADER)
                .row(2, "1", "CPVC pipe", "m", num("300"), num("180"), num("50000"));
        byte[] jsonl = concat(civil.jsonl(), electrical.jsonl(), plumbing.jsonl());
        serve(meta(sheet(0, "Civil", false), sheet(1, "Electrical", false), sheet(2, "Plumbing", false)), jsonl);

        List<BoqSheetDto> found = sheets.detect(documentId);
        assertThat(found).extracting(BoqSheetDto::sheetName).containsExactly("Civil", "Electrical", "Plumbing");
        assertThat(found.get(1).headerRow()).isEqualTo(2);
        assertThat(found.get(1).columns()).containsEntry("quantity", 3).containsEntry("unit", 4);
        sheets.extract(documentId, found.get(0).id(), mapping(found.get(0)));
        sheets.extract(documentId, found.get(1).id(), withPrefix(found.get(1), "E-"));
        BoqSheetDto plumbingRead = sheets.extract(documentId, found.get(2).id(), withPrefix(found.get(2), "P-"));
        assertThat(plumbingRead.summary().items()).isEqualTo(1);
        assertThat(candidates(null)).hasSize(4);
        assertThat(candidates(null).stream().filter(c -> c.naturalKey().equals("Plumbing!2")).findFirst().orElseThrow()
                .flags()).extracting(f -> f.code()).containsExactly("AMOUNT_MISMATCH");

        BoqSheetDto again = sheets.extract(documentId, found.get(0).id(), mapping(found.get(0)));

        assertThat(again.segmentId()).isEqualTo(sheets.list(documentId).get(0).segmentId());
        assertThat(candidates(null)).as("the sheet's rows replaced, not added to").hasSize(4);
        assertThat(review.list(packageId, CandidateKind.BOQ_ITEM, null, again.segmentId(), 0, 50).getContent())
                .extracting(CandidateDto::naturalKey).containsExactlyInAnyOrder("Civil!2", "Civil!3");
        assertThat(candidates(null)).extracting(c -> c.payload().get("itemCode"))
                .containsExactlyInAnyOrder("1", "2", "E-1", "P-1");
    }

    @Test
    void applyRefusesWithNoContractChosenAndWithContractBillingOff() {
        serve(meta(sheet(0, "BOQ", false)), new BoqRows("BOQ", 0).row(1, (Object[]) HEADER)
                .row(2, "1", "Earthwork", "cum", num("100"), num("200"), num("20000")).jsonl());
        BoqSheetDto sheet = sheets.detect(documentId).get(0);
        sheets.extract(documentId, sheet.id(), mapping(sheet));
        review.decide(packageId, accept(candidates(null)));

        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.BOQ_ITEM))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("tender BOQ");

        packages.setBoqTarget(packageId, new BoqTargetRequest(contractId));
        when(modules.isEnabledForOrg(eq(ContractBoqPort.MODULE_ID), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.BOQ_ITEM))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Contract billing is not enabled");

        assertThat(candidates(CandidateStatus.ACCEPTED)).as("the accepted rows stay as the tender BOQ").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_boq_item WHERE organization_id = ?", Long.class,
                orgId)).isZero();
    }

    @Test
    void theTargetMustBeInTheTenantAndThePackagesProjectAndStaysOnceApplied() {
        assertThatThrownBy(() -> packages.setBoqTarget(packageId, new BoqTargetRequest(otherOrgContractId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> packages.setBoqTarget(packageId, new BoqTargetRequest(otherProjectContractId)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("another project");
        assertThat(packages.setBoqTarget(packageId, new BoqTargetRequest(contractId)).subContractId()).isEqualTo(contractId);

        serve(meta(sheet(0, "BOQ", false)), new BoqRows("BOQ", 0).row(1, (Object[]) HEADER)
                .row(2, "1", "Earthwork", "cum", num("100"), num("200"), num("20000")).jsonl());
        BoqSheetDto sheet = sheets.detect(documentId).get(0);
        sheets.extract(documentId, sheet.id(), mapping(sheet));
        review.decide(packageId, accept(candidates(null)));
        apply.apply(packageId, CandidateKind.BOQ_ITEM);

        assertThatThrownBy(() -> packages.setBoqTarget(packageId, new BoqTargetRequest(null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("cannot change");
    }

    @Test
    void anotherTenantSeesNoSheetAndCannotReadOne() {
        serve(meta(sheet(0, "BOQ", false)), new BoqRows("BOQ", 0).row(1, (Object[]) HEADER).jsonl());
        BoqSheetDto sheet = sheets.detect(documentId).get(0);

        TenantContext.setCurrentOrgId(otherOrgId);

        assertThatThrownBy(() -> sheets.list(documentId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sheets.extract(documentId, sheet.id(), mapping(sheet)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String prefix() {
        return DocumentIngestionStorageLayout.outputPrefix(orgId, packageId, documentId);
    }

    private void serve(String meta, byte[] tables) {
        when(storage.readObject(eq(prefix() + DocumentIngestionStorageLayout.META_FILE), anyLong()))
                .thenReturn(Optional.of(new FileStorageService.StoredObject("m", "application/json",
                        meta.getBytes(StandardCharsets.UTF_8))));
        when(storage.readObject(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.TABLES_FILE)),
                anyLong())).thenReturn(Optional.of(new FileStorageService.StoredObject("t", "application/jsonl", tables)));
    }

    private static String sheet(int index, String name, boolean hidden) {
        return "{\"index\":" + index + ",\"name\":\"" + name + "\",\"hidden\":" + hidden + "}";
    }

    private static String meta(String... sheets) {
        return "{\"fileType\":\"XLSX\",\"sheets\":[" + String.join(",", sheets) + "],\"warnings\":[]}";
    }

    private static byte[] concat(byte[]... parts) {
        StringBuilder s = new StringBuilder();
        for (byte[] p : parts) {
            s.append(new String(p, StandardCharsets.UTF_8));
        }
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static BoqMappingRequest mapping(BoqSheetDto sheet) {
        return new BoqMappingRequest(sheet.headerRow(), sheet.columns(), null);
    }

    private static BoqMappingRequest withPrefix(BoqSheetDto sheet, String prefix) {
        return new BoqMappingRequest(sheet.headerRow(), sheet.columns(), prefix);
    }

    private List<CandidateDto> candidates(CandidateStatus status) {
        return review.list(packageId, CandidateKind.BOQ_ITEM, status, null, 0, 200).getContent();
    }

    private List<CandidateDto> allCandidates() {
        List<CandidateDto> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<CandidateDto> rows = review.list(packageId, CandidateKind.BOQ_ITEM, null, null, page, 200).getContent();
            if (rows.isEmpty()) {
                return all;
            }
            all.addAll(rows);
        }
    }

    private static List<CandidateDecision> accept(List<CandidateDto> rows) {
        return rows.stream().map(c -> new CandidateDecision(c.id(), Action.ACCEPT, null,
                c.flags().stream().map(f -> f.code()).toList())).toList();
    }

    private Map<String, Long> liveCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        LIVE_TABLES.forEach(table -> counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)));
        return counts;
    }

    private Long organization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name + UUID.randomUUID());
        org.setOrganizationAddress("address");
        org.setOrganizationEmail(UUID.randomUUID() + "@example.test");
        org.setOrganizationPhone("0000000000");
        return organizations.save(org).getId();
    }

    private Long project(Long org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(organizations.findById(org).orElseThrow());
        TenantContext.setCurrentOrgId(org);
        try {
            return projects.save(project).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private Long contract(Long org, Long project, String name) {
        SubContract c = new SubContract();
        c.setOrganization(organizations.findById(org).orElseThrow());
        c.setProjectId(project);
        c.setContractName(name);
        c.setContractorName("Sample Builders");
        c.setContractValue(BigDecimal.valueOf(2500000));
        TenantContext.setCurrentOrgId(org);
        try {
            return subContracts.save(c).getId();
        } finally {
            TenantContext.clear();
        }
    }
}
