package org.tornotron.echno_backend.billing.gateway;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.InitiateMandateCommand;
import org.tornotron.echno_backend.billing.gateway.dto.NotifyInfo;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandatePolicyTest {

    private static final NotifyInfo EMAIL = new NotifyInfo("buyer@example.com", null);
    private static final long BELOW_CAP = 1_499_900L;
    private static final long ABOVE_CAP = 1_500_100L;

    private final MandatePolicy policy = new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE);

    @Test
    void theCapIsFifteenThousandRupeesByDefault() {
        assertThat(policy.afaCapPaise()).isEqualTo(1_500_000L);
        assertThat(policy.requiresPerChargeAfa(1_500_000L)).isFalse();
        assertThat(policy.requiresPerChargeAfa(1_500_001L)).isTrue();
    }

    @Test
    void rupeesConvertToPaiseExactly() {
        assertThat(MandatePolicy.toPaise(new BigDecimal("4999.00"))).isEqualTo(499_900L);
        assertThat(MandatePolicy.toPaise(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(MandatePolicy.toPaise(null)).isZero();
        Plan plan = Plan.builder().code("p").monthlyPrice(new BigDecimal("100")).annualPrice(new BigDecimal("1000")).build();
        assertThat(MandatePolicy.cycleAmountPaise(plan, BillingPeriod.MONTHLY)).isEqualTo(10_000L);
        assertThat(MandatePolicy.cycleAmountPaise(plan, BillingPeriod.ANNUAL)).isEqualTo(100_000L);
    }

    @Test
    void aPlanAboveTheCapIsRefusedUnlessPerChargeAuthenticationIsAccepted() {
        assertThatThrownBy(() -> policy.validateCreate(command(1, false), ABOVE_CAP))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("above the RBI cap");
        assertThatCode(() -> policy.validateCreate(command(1, true), ABOVE_CAP)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateCreate(command(1, false), BELOW_CAP)).doesNotThrowAnyException();
    }

    @Test
    void theCapAppliesToTheWholeDebitSoQuantityCounts() {
        assertThatCode(() -> policy.validateCreate(command(1, false), 800_000L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateCreate(command(2, false), 800_000L))
                .isInstanceOf(MandatePolicyViolationException.class);
    }

    @Test
    void anOverflowingCycleAmountIsRefusedRatherThanWrapped() {
        CreateSubscriptionCommand huge = new CreateSubscriptionCommand(
                1L, "p", BillingPeriod.MONTHLY, Integer.MAX_VALUE, 0, null, EMAIL, false);
        assertThatThrownBy(() -> policy.validateCreate(huge, Long.MAX_VALUE / 2))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("overflows");
    }

    @Test
    void aSubscriptionNeedsAChannelForThePreDebitNotice() {
        CreateSubscriptionCommand noChannel = new CreateSubscriptionCommand(
                1L, "p", BillingPeriod.MONTHLY, 1, 0, null, new NotifyInfo(" ", null), false);
        assertThatThrownBy(() -> policy.validateCreate(noChannel, BELOW_CAP))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("pre-debit");
        CreateSubscriptionCommand nullNotify = new CreateSubscriptionCommand(
                1L, "p", BillingPeriod.MONTHLY, 1, 0, null, null, false);
        assertThatThrownBy(() -> policy.validateCreate(nullNotify, BELOW_CAP))
                .isInstanceOf(MandatePolicyViolationException.class);
        CreateSubscriptionCommand phoneOnly = new CreateSubscriptionCommand(
                1L, "p", BillingPeriod.MONTHLY, 1, 0, null, new NotifyInfo(null, "+919999999999"), false);
        assertThatCode(() -> policy.validateCreate(phoneOnly, BELOW_CAP)).doesNotThrowAnyException();
    }

    @Test
    void anUnpricedPlanOrBadQuantityIsRefused() {
        assertThatThrownBy(() -> policy.validateCreate(command(1, false), 0L))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("no price");
        assertThatThrownBy(() -> policy.validateCreate(command(0, false), BELOW_CAP))
                .isInstanceOf(MandatePolicyViolationException.class);
    }

    @Test
    void aMandateNeedsAMethodACeilingAndAChannel() {
        assertThatCode(() -> policy.validateMandate(mandate(MandateMethod.UPI_AUTOPAY, 1_000L, EMAIL)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateMandate(mandate(MandateMethod.UNKNOWN, 1_000L, EMAIL)))
                .isInstanceOf(MandatePolicyViolationException.class);
        assertThatThrownBy(() -> policy.validateMandate(mandate(MandateMethod.CARD, 0L, EMAIL)))
                .isInstanceOf(MandatePolicyViolationException.class);
        assertThatThrownBy(() -> policy.validateMandate(mandate(MandateMethod.CARD, 1_000L, null)))
                .isInstanceOf(MandatePolicyViolationException.class);
    }

    @Test
    void theCapIsConfigurable() {
        BillingGatewayProperties properties = new BillingGatewayProperties();
        properties.setAfaCapPaise(500_000L);
        MandatePolicy lower = new MandatePolicy(properties);
        assertThat(lower.requiresPerChargeAfa(500_001L)).isTrue();
        assertThat(policy.requiresPerChargeAfa(500_001L)).isFalse();
    }

    private static CreateSubscriptionCommand command(int quantity, boolean acceptAfa) {
        return new CreateSubscriptionCommand(1L, "p", BillingPeriod.MONTHLY, quantity, 0, null, EMAIL, acceptAfa);
    }

    private static InitiateMandateCommand mandate(MandateMethod method, long max, NotifyInfo notifyInfo) {
        return new InitiateMandateCommand(1L, "cust_x", method, max, "INR", notifyInfo);
    }
}
