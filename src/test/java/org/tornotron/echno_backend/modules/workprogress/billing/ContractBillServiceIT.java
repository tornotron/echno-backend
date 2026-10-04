package org.tornotron.echno_backend.modules.workprogress.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentSource;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillLineStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementType;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillAdjustmentDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillCommentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillLineDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ClaimLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.CreateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.DeductionRuleRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ManualAdjustmentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ManualAdjustmentsRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.UpdateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingRecords;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.payable.PayableService;
import org.tornotron.echno_backend.payable.mapper.PayableMapperImpl;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractService;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractCreationDto;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapperImpl;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.ScheduleIntegrationSupport;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapperImpl;

/**
 * Running account and milestone bills on the real migration: the money worked through two RA
 * bills to the paisa (rounding, rule order, a cap running out, previous quantities carried), the
 * milestone gate and percent ceiling, the workflow rules, the separation of certifier and
 * approver, the hand-off to finance, the sub-contract's hold on billed milestones, and tenant
 * isolation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContractBillService.class, ContractBillingService.class, BillingSupport.class, ContractBillingRecords.class,
        PayableService.class, PayableMapperImpl.class, SubContractService.class, SubContractMapperImpl.class,
        WbsElementService.class, WbsElementMapperImpl.class, EmployeeMapperImpl.class, ShiftTimingMapperImpl.class,
        TenantEntityHelper.class, ScheduleIntegrationSupport.FixedClocks.class})
class ContractBillServiceIT extends ScheduleIntegrationSupport {

    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final long CERTIFIER = 990_001L;

    @Autowired
    private ContractBillService bills;

    @Autowired
    private ContractBillingService billing;

    @Autowired
    private SubContractService subContracts;

    @MockitoBean
    private UserContextService userContext;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    private Long userAId;

    @BeforeEach
    void actors() {
        userAId = (Long) entityManager.createQuery("SELECT e.user.id FROM Employee e WHERE e.id = :id")
                .setParameter("id", employeeAId).getSingleResult();
        as(userAId);
    }

    @Test
    void twoRunningAccountBillsComeToThePaisaAndHandTheNetToFinance() {
        contractA().setRetentionPercentage(new BigDecimal("5"));
        BoqItemDto footing = billing.addBoqItem(subContractAId, boq("CP-01", "500", "8500.50"));
        BoqItemDto brick = billing.addBoqItem(subContractAId, boq("BR-01", "4000", "1234.567"));
        assertThat(brick.rate()).isEqualByComparingTo("1234.57");
        assertThat(brick.amount()).isEqualByComparingTo("4938280.00");

        BillDto ra1 = bills.create(ra(AUG_1, AUG_31));
        assertThat(ra1.billNumber()).isEqualTo("RA-01");
        assertThat(ra1.status()).isEqualTo(BillStatus.DRAFT);
        assertThat(billing.listRules(subContractAId)).as("retention seeded from the contract record")
                .singleElement().satisfies(rule -> {
                    assertThat(rule.kind()).isEqualTo(DeductionKind.RETENTION);
                    assertThat(rule.rate()).isEqualByComparingTo("5");
                });
        billing.addRule(subContractAId, percentRule(DeductionKind.GST, "18"));
        billing.addRule(subContractAId, percentRule(DeductionKind.TDS, "1"));
        billing.addRule(subContractAId, new DeductionRuleRequest(DeductionKind.ADVANCE_RECOVERY, "Advance recovery",
                null, DeductionBasis.FIXED, null, new BigDecimal("10000"), new BigDecimal("15000"), null, 3));

        Map<String, BillLineDto> lines = byCode(ra1);
        bills.update(ra1.id(), claim(AUG_1, AUG_31, Map.of(lines.get("CP-01").id(), "120.255", lines.get("BR-01").id(), "800")));
        BillDto submitted = bills.submit(ra1.id());
        assertThat(submitted.grossClaimed()).isEqualByComparingTo("2009883.63");

        bills.saveMeasurement(ra1.id(), measure(Map.of(lines.get("CP-01").id(), "120.255", lines.get("BR-01").id(), "750")));
        BillDto verified = bills.verify(ra1.id());
        assertThat(byCode(verified).get("BR-01").status()).isEqualTo(BillLineStatus.PART_ACCEPTED);
        assertThat(byCode(verified).get("CP-01").status()).isEqualTo(BillLineStatus.VERIFIED);

        BillDto withVariation = bills.replaceManualAdjustments(ra1.id(), new ManualAdjustmentsRequest(List.of(
                new ManualAdjustmentRequest(DeductionKind.VARIATION, "Approved variation VO-1", null, new BigDecimal("25000")))));
        assertThat(withVariation.amountsFinal()).isFalse();
        assertThat(withVariation.adjustments()).filteredOn(BillAdjustmentDto::preview).hasSize(4);

        as(CERTIFIER);
        BillDto certified = bills.certify(ra1.id());
        assertThat(certified.status()).isEqualTo(BillStatus.CERTIFIED);
        assertThat(certified.amountsFinal()).isTrue();
        assertThat(certified.grossAmount()).isEqualByComparingTo("1948155.13");
        assertThat(amounts(certified)).containsExactlyInAnyOrderEntriesOf(Map.of(
                DeductionKind.RETENTION, new BigDecimal("98657.76"),
                DeductionKind.GST, new BigDecimal("355167.92"),
                DeductionKind.TDS, new BigDecimal("19731.55"),
                DeductionKind.ADVANCE_RECOVERY, new BigDecimal("10000.00"),
                DeductionKind.VARIATION, new BigDecimal("25000.00")));
        assertThat(certified.additionsTotal()).isEqualByComparingTo("380167.92");
        assertThat(certified.deductionsTotal()).isEqualByComparingTo("128389.31");
        assertThat(certified.netPayable()).isEqualByComparingTo("2199933.74");
        assertThat(certified.previousCertified()).isEqualByComparingTo("0");

        as(userAId);
        BillDto approved = bills.approve(ra1.id());
        assertThat(approved.status()).isEqualTo(BillStatus.APPROVED);
        assertThat(approved.selfApproved()).isFalse();
        Payable payable = entityManager.find(Payable.class, approved.payableId());
        assertThat(payable.getAmountRecorded()).isEqualByComparingTo("2199933.74");
        assertThat(payable.getPayableNumber()).isEqualTo("SC-" + subContractAId + "/RA-01");
        assertThat(payable.getProject().getId()).isEqualTo(projectAId);

        // The second bill carries the accepted quantities forward and the advance cap runs out.
        assertThatThrownBy(() -> bills.create(ra(AUG_1.plusDays(10), SEP_30)))
                .as("overlaps RA-01").isInstanceOf(InvalidRequestException.class);
        BillDto ra2 = bills.create(ra(SEP_1, SEP_30));
        Map<String, BillLineDto> lines2 = byCode(ra2);
        assertThat(lines2.get("CP-01").previousQuantity()).isEqualByComparingTo("120.255");
        assertThat(lines2.get("BR-01").previousQuantity()).isEqualByComparingTo("750");
        assertThatThrownBy(() -> bills.update(ra2.id(), claim(SEP_1, SEP_30, Map.of(lines2.get("CP-01").id(), "379.746"))))
                .as("one thousandth past the contract quantity").isInstanceOf(InvalidRequestException.class);
        bills.update(ra2.id(), claim(SEP_1, SEP_30, Map.of(lines2.get("CP-01").id(), "379.745")));
        bills.submit(ra2.id());
        assertThatThrownBy(() -> bills.saveMeasurement(ra2.id(), measure(Map.of(lines2.get("CP-01").id(), "379.746"))))
                .as("more accepted than claimed").isInstanceOf(InvalidRequestException.class);
        bills.saveMeasurement(ra2.id(), measure(Map.of(lines2.get("CP-01").id(), "379.745")));
        bills.verify(ra2.id());
        as(CERTIFIER);
        BillDto second = bills.certify(ra2.id());
        assertThat(second.grossAmount()).isEqualByComparingTo("3228022.37");
        assertThat(amounts(second).get(DeductionKind.ADVANCE_RECOVERY)).as("15,000 cap less 10,000 taken")
                .isEqualByComparingTo("5000.00");
        assertThat(second.netPayable()).isEqualByComparingTo("3610385.06");
        assertThat(second.previousCertified()).isEqualByComparingTo("1948155.13");
        assertThat(second.cumulativeCertified()).isEqualByComparingTo("5176177.50");
        assertThat(byCode(second).get("CP-01").balanceQuantity()).isEqualByComparingTo("0");

        assertThat(billing.contract(subContractAId).summary().certifiedToDate()).isEqualByComparingTo("5176177.50");
        assertThat(billing.listBoq(subContractAId)).filteredOn(item -> item.itemCode().equals("CP-01"))
                .singleElement().satisfies(item -> {
                    assertThat(item.certifiedQuantity()).isEqualByComparingTo("500");
                    assertThat(item.inUse()).isTrue();
                });
        assertThatThrownBy(() -> billing.deleteBoqItem(footing.id())).isInstanceOf(InvalidRequestException.class);
        assertThat(bills.events(ra1.id())).extracting(e -> e.type().name())
                .startsWith("APPROVED", "CERTIFIED", "ADJUSTMENTS_UPDATED", "VERIFIED");
    }

    @Test
    void aContractHasOneModelAndOneOpenBill() {
        billing.addBoqItem(subContractAId, boq("CP-01", "100", "10"));
        BillDto first = bills.create(ra(AUG_1, AUG_31));
        assertThatThrownBy(() -> bills.create(ra(SEP_1, SEP_30))).as("RA-01 is open")
                .isInstanceOf(InvalidRequestException.class);
        bills.cancel(first.id());
        Long milestone = addMilestone(contractA(), "Plinth", new BigDecimal("100000"), null);
        BillDto milestoneBill = bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null,
                milestone, null, null, null, null));
        assertThat(milestoneBill.billNumber()).as("a cancelled bill does not fix the model").isEqualTo("MB-02");
    }

    @Test
    void theFirstLiveBillFixesTheModel() {
        billing.addBoqItem(subContractAId, boq("CP-01", "100", "10"));
        BillDto first = bills.create(ra(AUG_1, AUG_31));
        bills.update(first.id(), claim(AUG_1, AUG_31, Map.of(byCode(first).get("CP-01").id(), "10")));
        bills.submit(first.id());
        bills.returnForCorrection(first.id(), new BillCommentRequest("Wrong period"));
        bills.cancel(first.id());
        BillDto second = bills.create(ra(AUG_1, AUG_31));
        assertThat(second.billNumber()).as("numbers are not reused").isEqualTo("RA-02");
        bills.update(second.id(), claim(AUG_1, AUG_31, Map.of(byCode(second).get("CP-01").id(), "10")));
        bills.submit(second.id());
        bills.saveMeasurement(second.id(), measure(Map.of(byCode(second).get("CP-01").id(), "10")));
        bills.verify(second.id());
        as(CERTIFIER);
        bills.certify(second.id());
        as(userAId);
        bills.approve(second.id());
        Long milestone = addMilestone(contractA(), "Plinth", new BigDecimal("100000"), null);
        assertThatThrownBy(() -> bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null,
                milestone, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("running account");
    }

    @Test
    void aMilestoneBillWaitsForItsRequirementsAndCannotPassTheMilestone() {
        SubContract contract = contractA();
        contract.setContractValue(new BigDecimal("10000000"));
        Long milestone = addMilestone(contract, "Structural frame", null, new BigDecimal("15"));
        MilestoneRequirementDto cubes = billing.addRequirement(subContractAId, milestone, new MilestoneRequirementRequest(
                "Cube tests", RequirementType.QUALITY_TEST, null, null, null, null, null, 0));
        billing.addRequirement(subContractAId, milestone, new MilestoneRequirementRequest(
                "Third party tests", RequirementType.QUALITY_TEST, null, false, null, null, null, 1));
        billing.addRule(subContractAId, percentRule(DeductionKind.RETENTION, "5"));

        BillDto mb = bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null, milestone,
                new BigDecimal("85"), "INV-77", "Tower B, level 5", null));
        assertThat(mb.billNumber()).isEqualTo("MB-01");
        assertThat(mb.milestoneValue()).isEqualByComparingTo("1500000.00");
        assertThat(mb.grossClaimed()).isEqualByComparingTo("1275000.00");
        bills.submit(mb.id());
        assertThatThrownBy(() -> bills.saveMeasurement(mb.id(), new MeasurementRequest(TODAY, "Anitha", "Vignesh",
                new BigDecimal("90"), null))).as("more than claimed").isInstanceOf(InvalidRequestException.class);
        bills.saveMeasurement(mb.id(), new MeasurementRequest(TODAY, "Anitha", "Vignesh", new BigDecimal("80"), null));
        bills.verify(mb.id());
        as(CERTIFIER);
        assertThatThrownBy(() -> bills.certify(mb.id())).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Cube tests").hasMessageNotContaining("Third party");
        billing.updateRequirement(cubes.id(), new MilestoneRequirementRequest("Cube tests", RequirementType.QUALITY_TEST,
                null, true, null, RequirementStatus.COMPLETED, "All passed", 0));
        BillDto certified = bills.certify(mb.id());
        assertThat(certified.grossAmount()).isEqualByComparingTo("1200000.00");
        assertThat(certified.deductionsTotal()).isEqualByComparingTo("60000.00");
        assertThat(certified.netPayable()).isEqualByComparingTo("1140000.00");

        as(userAId);
        bills.approve(certified.id());
        assertThatThrownBy(() -> bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null,
                milestone, new BigDecimal("25"), null, null, null)))
                .as("80 percent already certified").isInstanceOf(InvalidRequestException.class);
        BillDto rest = bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null, milestone,
                new BigDecimal("20"), null, null, null));
        assertThat(rest.milestoneCertifiedBeforePercent()).isEqualByComparingTo("80");
    }

    @Test
    void theCertifierCannotApproveUnlessASystemAdminAndThenItIsRecorded() {
        BillDto bill = certifiedBill();
        as(userAId);
        // certifiedBill() certified as user A as well
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        assertThatThrownBy(() -> bills.approve(bill.id())).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("someone else");
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        assertThat(bills.approve(bill.id()).selfApproved()).isTrue();
    }

    @Test
    void aReturnedCertificationIsUnfrozenAndItsFiguresStayOnTheTimeline() {
        BillDto bill = certifiedBill();
        BillDto returned = bills.returnForCorrection(bill.id(), new BillCommentRequest("Rate for CP-01 disputed"));
        assertThat(returned.status()).isEqualTo(BillStatus.RETURNED);
        assertThat(returned.amountsFinal()).isFalse();
        assertThat(returned.adjustments()).noneMatch(a -> a.source() == AdjustmentSource.RULE && !a.preview());
        assertThat(bills.events(bill.id()).get(0).note()).contains("Rate for CP-01 disputed").contains("net Rs. 100.00");
        assertThatThrownBy(() -> bills.approve(bill.id())).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aBilledMilestoneSurvivesAContractEditAndHoldsTheContract() {
        SubContract contract = contractA();
        Long milestone = addMilestone(contract, "Plinth", new BigDecimal("100000"), null);
        Long other = addMilestone(contract, "Roof", new BigDecimal("50000"), null);
        bills.create(new CreateBillRequest(subContractAId, BillingModel.MILESTONE, null, null, milestone,
                new BigDecimal("50"), null, null, null));

        SubContractCreationDto edit = new SubContractCreationDto();
        edit.setContractName("Renamed civil works");
        edit.setContractorName("Sree Builders");
        edit.setProjectId(projectAId);
        edit.setContractValue(new BigDecimal("2500000"));
        edit.setMilestones(List.of(milestoneDto(milestone, "Plinth complete"), milestoneDto(other, "Roof")));
        subContracts.update(subContractAId, edit);
        entityManager.flush();
        entityManager.clear();
        assertThat(contractA().getMilestones()).extracting(ContractMilestone::getId).containsExactlyInAnyOrder(milestone, other);

        edit.setMilestones(List.of(milestoneDto(milestone, "Plinth complete")));
        subContracts.update(subContractAId, edit);
        entityManager.flush();
        assertThat(contractA().getMilestones()).extracting(ContractMilestone::getId).containsExactly(milestone);

        edit.setMilestones(List.of());
        assertThatThrownBy(() -> subContracts.update(subContractAId, edit)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("bills");
        assertThatThrownBy(() -> subContracts.delete(subContractAId)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void anotherTenantsContractsAndBillsReadAsAbsent() {
        UUID foreignBill = asTenant(orgBId, () -> {
            billing.addBoqItem(subContractBId, boq("X-1", "10", "10"));
            return bills.create(new CreateBillRequest(subContractBId, BillingModel.RUNNING_ACCOUNT, AUG_1, AUG_31, null,
                    null, null, null, null)).id();
        });
        assertThatThrownBy(() -> bills.get(foreignBill)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bills.submit(foreignBill)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bills.events(foreignBill)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bills.create(new CreateBillRequest(subContractBId, BillingModel.RUNNING_ACCOUNT, AUG_1,
                AUG_31, null, null, null, null, null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> billing.listBoq(subContractBId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> billing.addRule(subContractBId, percentRule(DeductionKind.TDS, "1")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bills.list(null, null, null, null, 0, 20).getContent()).isEmpty();
        assertThat(billing.overview().openBills()).isZero();
        assertThat(billing.contracts(null, 0, 20).getContent()).extracting(c -> c.subContractId())
                .contains(subContractAId).doesNotContain(subContractBId);
    }

    // ---------------------------------------------------------------- helpers

    private BillDto certifiedBill() {
        billing.addBoqItem(subContractAId, boq("CP-01", "100", "10"));
        BillDto bill = bills.create(ra(AUG_1, AUG_31));
        UUID line = byCode(bill).get("CP-01").id();
        bills.update(bill.id(), claim(AUG_1, AUG_31, Map.of(line, "10")));
        bills.submit(bill.id());
        bills.saveMeasurement(bill.id(), measure(Map.of(line, "10")));
        bills.verify(bill.id());
        BillDto certified = bills.certify(bill.id());
        assertThat(certified.netPayable()).isEqualByComparingTo("100.00");
        return certified;
    }

    private void as(Long userId) {
        when(userContext.getCurrentUserId()).thenReturn(userId);
    }

    private SubContract contractA() {
        return entityManager.find(SubContract.class, subContractAId);
    }

    private Long addMilestone(SubContract contract, String name, BigDecimal amount, BigDecimal percent) {
        ContractMilestone milestone = new ContractMilestone();
        milestone.setName(name);
        milestone.setAmount(amount);
        milestone.setPaymentPercentage(percent);
        milestone.setTargetDate(SEP_30);
        milestone.setOrganization(contract.getOrganization());
        contract.addMilestone(milestone);
        entityManager.flush();
        return milestone.getId();
    }

    private static ContractMilestoneDto milestoneDto(Long id, String name) {
        ContractMilestoneDto dto = new ContractMilestoneDto();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }

    private CreateBillRequest ra(LocalDate from, LocalDate to) {
        return new CreateBillRequest(subContractAId, BillingModel.RUNNING_ACCOUNT, from, to, null, null, null, null, null);
    }

    private static BoqItemRequest boq(String code, String quantity, String rate) {
        return new BoqItemRequest(code, "Item " + code, "m3", new BigDecimal(quantity), new BigDecimal(rate), null, null);
    }

    private static DeductionRuleRequest percentRule(DeductionKind kind, String rate) {
        return new DeductionRuleRequest(kind, kind.name(), null, DeductionBasis.PERCENT, new BigDecimal(rate), null, null,
                null, null);
    }

    private static UpdateBillRequest claim(LocalDate from, LocalDate to, Map<UUID, String> quantities) {
        return new UpdateBillRequest(from, to, null, null, null, null, quantities.entrySet().stream()
                .map(e -> new ClaimLineRequest(e.getKey(), new BigDecimal(e.getValue()), null)).toList());
    }

    private static MeasurementRequest measure(Map<UUID, String> accepted) {
        return new MeasurementRequest(TODAY, "Anitha Rajendran", "Vignesh Kumar", null, accepted.entrySet().stream()
                .map(e -> new MeasurementLineRequest(e.getKey(), new BigDecimal(e.getValue()), new BigDecimal(e.getValue()), null))
                .toList());
    }

    private static Map<String, BillLineDto> byCode(BillDto bill) {
        return bill.lines().stream().collect(Collectors.toMap(BillLineDto::itemCode, Function.identity()));
    }

    private static Map<DeductionKind, BigDecimal> amounts(BillDto bill) {
        return bill.adjustments().stream().collect(Collectors.toMap(BillAdjustmentDto::kind, BillAdjustmentDto::amount));
    }
}
