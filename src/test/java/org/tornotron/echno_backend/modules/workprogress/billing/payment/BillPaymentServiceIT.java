package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillEventType;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillPaymentState;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillLineDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ClaimLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.CreateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.UpdateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.authorizer.PaymentAuthorizers;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentsDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.ProjectPaymentAuthorizerDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RaiseBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RecordBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGateway;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGatewayException;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGateways;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.FakeBillPaymentGateway;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutCommand;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutLookup;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingRecords;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.PayableAccessStubs;
import org.tornotron.echno_backend.payable.PayableService;
import org.tornotron.echno_backend.payable.mapper.PayableMapperImpl;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapperImpl;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.ScheduleIntegrationSupport;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapperImpl;

/**
 * Paying an approved bill on the real migration: the manual path and the provider path to the payable,
 * part payment, the one-in-flight rule, what a lost answer leaves behind, late and replayed answers,
 * and tenant isolation; and who may sign: only the project's named payment authorizer, never on a
 * payment they raised, and never a system admin by role alone. The provider is scripted through a mocked
 * {@link BillPaymentGateways}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BillPaymentService.class, BillPaymentStore.class, PaymentAuthorizers.class, ContractBillService.class, ContractBillingService.class,
        BillingSupport.class, ContractBillingRecords.class, PayableService.class, PayableMapperImpl.class,
        SubContractMapperImpl.class, WbsElementService.class, WbsElementMapperImpl.class, EmployeeMapperImpl.class,
        ShiftTimingMapperImpl.class, TenantEntityHelper.class, ScheduleIntegrationSupport.FixedClocks.class,
        PayableAccessStubs.AllowingEverything.class})
class BillPaymentServiceIT extends ScheduleIntegrationSupport {

    private static final long CERTIFIER = 990_001L;

    @Autowired
    private BillPaymentService payments;

    @Autowired
    private BillPaymentStore store;

    @Autowired
    private ContractBillService bills;

    @Autowired
    private ContractBillingService billing;

    @Autowired
    private PayableService payables;

    @MockitoBean
    private UserContextService userContext;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private BillPaymentGateways gateways;

    @Autowired
    private PaymentAuthorizers authorizers;

    /** Raises payments: the project manager of project A. */
    private Long userAId;

    /** Project A's payment authorizer. */
    private Long financeEmployeeId;
    private Long financeUserId;

    @BeforeEach
    void actors() {
        userAId = userOf(employeeAId);
        financeEmployeeId = employee(orgAId, "Meera Finance", EmployeeStatus.active);
        financeUserId = userOf(financeEmployeeId);
        authorizers.set(projectAId, financeEmployeeId, userAId);
        as(userAId);
        manual();
    }

    @Test
    void aManualPaymentRunsFromRequestToThePayable() {
        BillDto bill = approvedBill();
        BillPaymentsDto before = payments.list(bill.id());
        assertThat(before.provider()).isEqualTo(BillPaymentProvider.MANUAL);
        assertThat(before.amountDue()).isEqualByComparingTo("1000.00");
        assertThat(before.paymentState()).isEqualTo(BillPaymentState.UNPAID);

        BillPaymentsDto raised = payments.raise(bill.id(), request("400.00"));
        BillPaymentDto payment = only(raised);
        assertThat(payment.status()).isEqualTo(BillPaymentStatus.REQUESTED);
        assertThat(raised.inFlight()).isTrue();
        assertThat(raised.amountPaid()).as("nothing paid until it settles").isEqualByComparingTo("0");

        as(financeUserId);
        BillPaymentsDto authorized = payments.authorize(payment.id());
        assertThat(only(authorized).status()).isEqualTo(BillPaymentStatus.AUTHORIZED);
        assertThat(only(authorized).selfAuthorized()).isFalse();

        BillPaymentsDto recorded = payments.record(payment.id(), paid("UTR-1"));
        entityManager.flush();
        BillPaymentDto settled = only(recorded);
        assertThat(settled.status()).isEqualTo(BillPaymentStatus.SETTLED);
        assertThat(settled.bankReference()).isEqualTo("UTR-1");
        assertThat(settled.appliedAmount()).isEqualByComparingTo("400.00");
        assertThat(recorded.amountPaid()).isEqualByComparingTo("400.00");
        assertThat(recorded.amountDue()).isEqualByComparingTo("600.00");
        assertThat(recorded.paymentState()).isEqualTo(BillPaymentState.PART_PAID);
        assertThat(recorded.inFlight()).isFalse();
        assertThat(payables.getPayableById(bill.payableId()).getAmountPaid()).isEqualByComparingTo("400.00");
        assertThat(bills.events(bill.id())).extracting(e -> e.type())
                .startsWith(BillEventType.PAYMENT_SETTLED, BillEventType.PAYMENT_AUTHORIZED, BillEventType.PAYMENT_REQUESTED);
    }

    @Test
    void theSecondPartPaymentFinishesTheBill() {
        BillDto bill = approvedBill();
        payFully(bill.id(), "400.00", "UTR-1");
        BillPaymentsDto done = payFully(bill.id(), "600.00", "UTR-2");
        assertThat(done.paymentState()).isEqualTo(BillPaymentState.PAID);
        assertThat(done.amountDue()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> payments.raise(bill.id(), request("1.00")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("fully paid");
    }

    @Test
    void aBillThatIsNotApprovedCannotBePaidAndNeitherCanMoreThanIsDue() {
        BillDto draft = openBill();
        assertThatThrownBy(() -> payments.raise(draft.id(), request("1.00")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("only an approved bill");

        BillDto bill = approve(draft);
        assertThatThrownBy(() -> payments.raise(bill.id(), request("1000.01")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("is due");
    }

    @Test
    void aBillHasOnePaymentInFlight() {
        BillDto bill = approvedBill();
        UUID first = only(payments.raise(bill.id(), request("100.00"))).id();
        assertThatThrownBy(() -> payments.raise(bill.id(), request("100.00")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("in progress");

        as(financeUserId);
        BillPaymentsDto rejected = payments.reject(first, "Wrong amount");
        assertThat(only(rejected).status()).isEqualTo(BillPaymentStatus.REJECTED);
        assertThat(only(rejected).failureReason()).isEqualTo("Wrong amount");
        assertThat(rejected.amountPaid()).as("a rejected payment never touched the payable").isEqualByComparingTo("0");

        assertThat(payments.raise(bill.id(), request("100.00")).payments()).hasSize(2);
    }

    @Test
    void aPaymentRecordedInFinanceMeanwhileStopsTheAuthorization() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("800.00"))).id();
        payables.recordPayment(bill.payableId(), new BigDecimal("500.00"));
        entityManager.flush();
        as(financeUserId);
        assertThatThrownBy(() -> payments.authorize(id))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("is due");
    }

    @Test
    void theAuthorizationIsRecordedWithTheCapacityItWasGivenIn() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        as(financeUserId);
        BillPaymentDto authorized = only(payments.authorize(id));
        assertThat(authorized.authorizedAs()).isEqualTo(BillPaymentAuthority.PROJECT_AUTHORIZER);
        assertThat(authorized.authorizedByName()).isEqualTo("Meera Finance");
        assertThat(authorized.selfAuthorized()).isFalse();
        assertThat(bills.events(bill.id())).filteredOn(e -> e.type() == BillEventType.PAYMENT_AUTHORIZED)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.note()).contains("by the project's payment authorizer");
                    assertThat(e.actorName()).isEqualTo("Meera Finance");
                });
    }

    @Test
    void aSystemAdminWhoIsNotTheAuthorizerCannotSign() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        // The roles a mocked session holds: every one of them, system admin included.
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        Long admin = userOf(employee(orgAId, "Org Admin", EmployeeStatus.active));
        as(admin);
        BillPaymentDto seen = only(payments.list(bill.id()));
        assertThat(seen.readerMayAuthorize()).isFalse();
        assertThat(seen.authorizeBlock()).isEqualTo(BillPaymentAuthorizeBlock.NOT_THE_AUTHORIZER);
        assertThatThrownBy(() -> payments.authorize(id))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Meera Finance");
        assertThatThrownBy(() -> payments.reject(id, "No"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(only(payments.list(bill.id())).status()).isEqualTo(BillPaymentStatus.REQUESTED);
    }

    @Test
    void theAuthorizerCannotSignAPaymentTheyRaisedSoItWaitsUntilAnotherIsNamed() {
        BillDto bill = approvedBill();
        as(financeUserId);
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();

        BillPaymentDto own = only(payments.list(bill.id()));
        assertThat(own.readerMayAuthorize()).isFalse();
        assertThat(own.authorizeBlock()).isEqualTo(BillPaymentAuthorizeBlock.OWN_REQUEST);
        assertThatThrownBy(() -> payments.authorize(id))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("You raised this payment");

        as(userAId);
        assertThat(only(payments.list(bill.id())).authorizeBlock())
                .as("anyone else is told the only authorizer raised it")
                .isEqualTo(BillPaymentAuthorizeBlock.RAISED_BY_THE_AUTHORIZER);

        Long other = employee(orgAId, "Arun Accounts", EmployeeStatus.active);
        authorizers.set(projectAId, other, userAId);
        as(userOf(other));
        BillPaymentDto now = only(payments.list(bill.id()));
        assertThat(now.readerMayAuthorize()).isTrue();
        assertThat(now.authorizeBlock()).isNull();
        assertThat(only(payments.authorize(id)).status()).isEqualTo(BillPaymentStatus.AUTHORIZED);
    }

    @Test
    void theAuthorizerMayWithdrawTheirOwnRequest() {
        BillDto bill = approvedBill();
        as(financeUserId);
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        assertThat(only(payments.reject(id, "Raised by mistake")).status()).isEqualTo(BillPaymentStatus.REJECTED);
    }

    @Test
    void aProjectWithNoAuthorizerHoldsItsPaymentsAndSaysWhy() {
        authorizers.clear(projectAId, userAId);
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        BillPaymentsDto view = payments.list(bill.id());
        assertThat(view.authorizerEmployeeId()).isNull();
        assertThat(only(view).authorizeBlock()).isEqualTo(BillPaymentAuthorizeBlock.NO_AUTHORIZER);
        as(financeUserId);
        assertThatThrownBy(() -> payments.authorize(id))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("no payment authorizer");
    }

    @Test
    void anotherProjectsAuthorizerCannotSign() {
        authorizers.clear(projectAId, userAId);
        authorizers.set(projectA2Id, financeEmployeeId, userAId);
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        as(financeUserId);
        assertThatThrownBy(() -> payments.authorize(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAuthorizerWhoHasLeftNoLongerSignsAndCannotBeNamed() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        entityManager.find(Employee.class, financeEmployeeId).setStatus(EmployeeStatus.resigned);
        entityManager.flush();
        assertThat(only(payments.list(bill.id())).authorizeBlock()).isEqualTo(BillPaymentAuthorizeBlock.NO_AUTHORIZER);
        as(financeUserId);
        assertThatThrownBy(() -> payments.authorize(id))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("no longer active");
        assertThatThrownBy(() -> authorizers.set(projectA2Id, financeEmployeeId, userAId))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("resigned");
    }

    @Test
    void everyChangeOfAuthorizerIsKept() {
        Long other = employee(orgAId, "Arun Accounts", EmployeeStatus.active);
        authorizers.set(projectAId, other, userAId);
        authorizers.set(projectAId, other, userAId);
        ProjectPaymentAuthorizerDto cleared = authorizers.clear(projectAId, userAId);
        assertThat(cleared.employeeId()).isNull();
        // The test clock is fixed, so the three share a timestamp and their order is not under test here.
        assertThat(cleared.changes()).as("naming the same person again records nothing")
                .extracting(c -> c.previousEmployeeName() + " -> " + c.employeeName())
                .containsExactlyInAnyOrder("null -> Meera Finance", "Meera Finance -> Arun Accounts",
                        "Arun Accounts -> null");
        assertThat(cleared.changes()).allSatisfy(c -> assertThat(c.changedByName()).isEqualTo("Ravi Kumar"));
    }

    @Test
    void anAuthorizerIsNamedOnlyFromTheOrganizationOnItsOwnProjects() {
        assertThatThrownBy(() -> authorizers.set(projectAId, employeeBId, userAId))
                .as("an employee of another organization").isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authorizers.set(projectBId, financeEmployeeId, userAId))
                .as("a project of another organization").isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authorizers.view(projectBId, userAId)).isInstanceOf(ResourceNotFoundException.class);
        asTenant(orgBId, () -> {
            assertThatThrownBy(() -> authorizers.view(projectAId, financeUserId))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(authorizers.holds(orgBId, projectAId, financeUserId))
                    .as("org A's naming means nothing under org B").isFalse();
            assertThat(authorizers.view(projectBId, financeUserId).employeeId()).isNull();
            return null;
        });
    }

    @Test
    void anotherOrganizationsAuthorizerCannotSignHere() {
        Long orgBFinance = employee(orgBId, "Other Org Finance", EmployeeStatus.active);
        asTenant(orgBId, () -> authorizers.set(projectBId, orgBFinance, null));
        Long orgBUser = asTenant(orgBId, () -> userOf(orgBFinance));
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        as(orgBUser);
        assertThatThrownBy(() -> payments.authorize(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aManualPaymentCannotBePaidInTheFutureOrBeforeItIsSigned() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        assertThatThrownBy(() -> payments.record(id, paid("UTR-1")))
                .as("recording is the authorizer's").isInstanceOf(AccessDeniedException.class);
        as(financeUserId);
        assertThatThrownBy(() -> payments.record(id, paid("UTR-1")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("only an authorized");
        payments.authorize(id);
        assertThatThrownBy(() -> payments.record(id, new RecordBillPaymentRequest("UTR-1", TODAY.plusDays(1))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> payments.sync(id))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("manual");
    }

    @Test
    void theFakeProviderSettlesOnSyncAndIsLabelledATest() {
        provider(new FakeBillPaymentGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("400.00"))).id();
        as(financeUserId);
        BillPaymentDto sent = only(payments.authorize(id));
        assertThat(sent.status()).isEqualTo(BillPaymentStatus.PROCESSING);
        assertThat(sent.test()).isTrue();
        assertThat(sent.beneficiaryAccountLast4()).isEqualTo("6789");

        BillPaymentsDto settled = payments.sync(id);
        entityManager.flush();
        assertThat(only(settled).status()).isEqualTo(BillPaymentStatus.SETTLED);
        assertThat(only(settled).bankReference()).startsWith("TEST-");
        assertThat(settled.amountPaid()).isEqualByComparingTo("400.00");
        assertThat(bills.events(bill.id())).extracting(e -> e.note()).allMatch(
                note -> note == null || !note.contains("Payment of"), "every payment event says it is a test");
    }

    @Test
    void theFakeProviderFailsAnAmountEndingInThirteenPaiseAndAnotherCanBeRaised() {
        provider(new FakeBillPaymentGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.13"))).id();
        as(financeUserId);
        payments.authorize(id);
        BillPaymentsDto failed = payments.sync(id);
        assertThat(only(failed).status()).isEqualTo(BillPaymentStatus.FAILED);
        assertThat(only(failed).failureReason()).contains("Test failure");
        assertThat(failed.amountPaid()).isEqualByComparingTo("0");
        assertThat(failed.inFlight()).isFalse();
        assertThat(payments.raise(bill.id(), request("100.00")).payments()).hasSize(2);
    }

    @Test
    void aProviderCannotBeUsedWithoutTheContractsBankDetails() {
        provider(new FakeBillPaymentGateway());
        BillDto bill = approvedBill();
        assertThat(payments.list(bill.id()).beneficiaryReady()).isFalse();
        assertThatThrownBy(() -> payments.raise(bill.id(), request("100.00")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("bank account");
    }

    @Test
    void aSendWithNoAnswerStaysAuthorizedAndTheRetryUsesTheSamePaymentId() {
        ScriptedGateway gateway = new ScriptedGateway();
        provider(gateway);
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("400.00"))).id();
        as(financeUserId);

        gateway.nextSend = () -> {
            throw new BillPaymentGatewayException("The provider gave no usable answer");
        };
        BillPaymentDto lost = only(payments.authorize(id));
        assertThat(lost.status()).isEqualTo(BillPaymentStatus.AUTHORIZED);
        assertThat(lost.lastError()).contains("no usable answer");

        gateway.nextSend = () -> new PayoutResult(PayoutResult.Outcome.PROCESSING, "pout_1", null, null, 40000L);
        BillPaymentDto resent = only(payments.sync(id));
        assertThat(resent.status()).isEqualTo(BillPaymentStatus.PROCESSING);
        assertThat(resent.lastError()).isNull();
        assertThat(gateway.sent).hasSize(2).allSatisfy(command -> assertThat(command.paymentId()).isEqualTo(id));
    }

    @Test
    void lateAndRepeatedAnswersOnlyMoveAPaymentForward() {
        provider(new ScriptedGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = sentPayment(bill, "400.00");

        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 40000L));
        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 40000L));
        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.PROCESSING, "pout_1", null, null, 40000L));
        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.FAILED, "pout_1", null, "Too late", 40000L));
        entityManager.flush();

        BillPaymentsDto view = payments.list(bill.id());
        assertThat(only(view).status()).isEqualTo(BillPaymentStatus.SETTLED);
        assertThat(view.amountPaid()).as("the replay was not paid twice").isEqualByComparingTo("400.00");
    }

    @Test
    void anAnswerForADifferentAmountIsIgnored() {
        provider(new ScriptedGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = sentPayment(bill, "400.00");
        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 99L));
        assertThat(only(payments.list(bill.id())).status()).isEqualTo(BillPaymentStatus.PROCESSING);
    }

    @Test
    void aReversalGivesTheAmountBackToThePayable() {
        provider(new ScriptedGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = sentPayment(bill, "400.00");
        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 40000L));
        entityManager.flush();

        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.REVERSED, "pout_1", null, "Account closed", 40000L));
        entityManager.flush();
        BillPaymentsDto view = payments.list(bill.id());
        assertThat(only(view).status()).isEqualTo(BillPaymentStatus.REVERSED);
        assertThat(only(view).failureReason()).isEqualTo("Account closed");
        assertThat(view.amountPaid()).isEqualByComparingTo("0");
        assertThat(view.amountDue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void moneyThatWentOutPastWhatIsDueSettlesForWhatIsDueAndSaysSo() {
        provider(new ScriptedGateway());
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = sentPayment(bill, "800.00");
        payables.recordPayment(bill.payableId(), new BigDecimal("500.00"));
        entityManager.flush();

        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 80000L));
        entityManager.flush();
        BillPaymentsDto view = payments.list(bill.id());
        assertThat(only(view).status()).isEqualTo(BillPaymentStatus.SETTLED);
        assertThat(only(view).appliedAmount()).isEqualByComparingTo("500.00");
        assertThat(view.amountDue()).isEqualByComparingTo("0");
        assertThat(bills.events(bill.id())).extracting(e -> e.note()).anyMatch(note -> note.contains("reconcile"));
    }

    @Test
    void aSendThatGotThroughAfterARejectionStillShowsThePayment() {
        ScriptedGateway gateway = new ScriptedGateway();
        provider(gateway);
        withBankDetails();
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("400.00"))).id();
        as(financeUserId);
        gateway.nextSend = () -> {
            throw new BillPaymentGatewayException("The provider gave no usable answer");
        };
        payments.authorize(id);
        assertThat(only(payments.reject(id, "Stop this")).status()).isEqualTo(BillPaymentStatus.REJECTED);

        store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "pout_1", "UTR-9", null, 40000L));
        entityManager.flush();
        BillPaymentsDto view = payments.list(bill.id());
        assertThat(only(view).status()).isEqualTo(BillPaymentStatus.SETTLED);
        assertThat(view.amountPaid()).isEqualByComparingTo("400.00");
    }

    @Test
    void aPaymentAProviderHasAcknowledgedCannotBeRejected() {
        provider(new ScriptedGateway());
        withBankDetails();
        UUID id = sentPayment(approvedBill(), "400.00");
        as(financeUserId);
        assertThatThrownBy(() -> payments.reject(id, "Too late"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("no longer");
    }

    @Test
    void anotherTenantsBillAndPaymentReadAsAbsent() {
        BillDto bill = approvedBill();
        UUID id = only(payments.raise(bill.id(), request("100.00"))).id();
        asTenant(orgBId, () -> {
            assertThatThrownBy(() -> payments.list(bill.id())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> payments.raise(bill.id(), request("1.00"))).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> payments.authorize(id)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> payments.reject(id, "No")).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> store.applyOutcome(id, new PayoutResult(PayoutResult.Outcome.SETTLED, "x", null, null, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    private UUID sentPayment(BillDto bill, String amount) {
        UUID id = only(payments.raise(bill.id(), request(amount))).id();
        as(financeUserId);
        assertThat(only(payments.authorize(id)).status()).isEqualTo(BillPaymentStatus.PROCESSING);
        as(userAId);
        return id;
    }

    private BillPaymentsDto payFully(UUID billId, String amount, String reference) {
        as(userAId);
        BillPaymentsDto raised = payments.raise(billId, request(amount));
        UUID id = raised.payments().getFirst().id();
        as(financeUserId);
        payments.authorize(id);
        BillPaymentsDto done = payments.record(id, paid(reference));
        entityManager.flush();
        as(userAId);
        return done;
    }

    private BillDto approvedBill() {
        return approve(openBill());
    }

    private BillDto openBill() {
        billing.addBoqItem(subContractAId, new BoqItemRequest("CP-01", "RCC", "m3", new BigDecimal("100"),
                new BigDecimal("100"), null, null));
        return bills.create(new CreateBillRequest(subContractAId, BillingModel.RUNNING_ACCOUNT, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31), null, null, null, null, null, null));
    }

    private BillDto approve(BillDto bill) {
        UUID line = line(bill).id();
        bills.update(bill.id(), new UpdateBillRequest(bill.periodFrom(), bill.periodTo(), null, null, null, null,
                List.of(new ClaimLineRequest(line, new BigDecimal("10"), null))));
        bills.submit(bill.id());
        bills.saveMeasurement(bill.id(), new MeasurementRequest(TODAY, "Site engineer", null, null,
                List.of(new MeasurementLineRequest(line, new BigDecimal("10"), new BigDecimal("10"), null))));
        bills.verify(bill.id());
        as(CERTIFIER);
        bills.certify(bill.id());
        as(userAId);
        BillDto approved = bills.approve(bill.id());
        assertThat(approved.netPayable()).isEqualByComparingTo("1000.00");
        return approved;
    }

    private static BillLineDto line(BillDto bill) {
        return bill.lines().getFirst();
    }

    private void withBankDetails() {
        SubContract contract = entityManager.find(SubContract.class, subContractAId);
        contract.setBankAccountNumber("0123456789");
        contract.setBankIfsc("FDRL0001234");
    }

    private void manual() {
        when(gateways.forOrganization(any())).thenReturn(java.util.Optional.empty());
        when(gateways.providerFor(any())).thenReturn(BillPaymentProvider.MANUAL);
    }

    private void provider(BillPaymentGateway gateway) {
        when(gateways.forOrganization(any())).thenReturn(java.util.Optional.of(gateway));
        when(gateways.providerFor(any())).thenReturn(gateway.provider());
        when(gateways.require(any(), any())).thenReturn(gateway);
    }

    private void as(Long userId) {
        when(userContext.getCurrentUserId()).thenReturn(userId);
    }

    private Long userOf(Long employeeId) {
        return (Long) entityManager.createQuery("SELECT e.user.id FROM Employee e WHERE e.id = :id")
                .setParameter("id", employeeId).getSingleResult();
    }

    /** An employee with a sign-in, in this test's transaction, so it goes when the test does. */
    private Long employee(Long orgId, String name, EmployeeStatus status) {
        User user = new User();
        user.setKeycloakId("payauth-it-" + UUID.randomUUID());
        user.setName(name);
        entityManager.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(entityManager.find(Organization.class, orgId));
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("PAY-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(UUID.randomUUID().toString().substring(0, 12) + "@emp.test");
        employee.setDateOfBirth(java.time.LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setStatus(status);
        employee.setOrgRoles(new java.util.HashSet<>());
        entityManager.persist(employee);
        entityManager.flush();
        return employee.getId();
    }

    private static RaiseBillPaymentRequest request(String amount) {
        return new RaiseBillPaymentRequest(new BigDecimal(amount), null);
    }

    private static RecordBillPaymentRequest paid(String reference) {
        return new RecordBillPaymentRequest(reference, TODAY);
    }

    private static BillPaymentDto only(BillPaymentsDto view) {
        return view.payments().getFirst();
    }

    /** A provider whose next answer the test chooses, recording what it was asked to send. */
    private static final class ScriptedGateway implements BillPaymentGateway {
        private final List<PayoutCommand> sent = new java.util.ArrayList<>();
        private java.util.function.Supplier<PayoutResult> nextSend = () -> new PayoutResult(
                PayoutResult.Outcome.PROCESSING, "pout_1", null, null, null);

        @Override
        public BillPaymentProvider provider() {
            return BillPaymentProvider.RAZORPAY;
        }

        @Override
        public PayoutResult send(PayoutCommand command) {
            sent.add(command);
            return nextSend.get();
        }

        @Override
        public PayoutResult fetch(PayoutLookup lookup) {
            return new PayoutResult(PayoutResult.Outcome.PROCESSING, lookup.providerRef(), null, null, null);
        }
    }
}
