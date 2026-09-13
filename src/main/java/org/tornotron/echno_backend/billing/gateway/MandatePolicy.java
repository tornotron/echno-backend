package org.tornotron.echno_backend.billing.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.InitiateMandateCommand;
import org.tornotron.echno_backend.billing.gateway.dto.NotifyInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The RBI e-mandate rules every adapter enforces before it talks to a provider. They are
 * product rules under the recurring-payment framework, so they sit on the neutral side of the
 * port rather than inside the Razorpay adapter.
 *
 * <ul>
 *   <li>A recurring debit above the additional-factor-of-authentication cap (15,000 rupees by
 *       default, {@code echno.billing.afa-cap-paise}) needs the payer to authenticate every
 *       cycle. A plan priced above the cap is allowed only when the buyer has accepted that.</li>
 *   <li>The payer is notified at least 24 hours before each debit, so a subscription cannot be
 *       created without a channel to notify on.</li>
 *   <li>A mandate ceiling must cover the cycle amount, and a mandate always needs a method.</li>
 * </ul>
 */
@Component
public class MandatePolicy {

    /** The RBI additional-factor-of-authentication ceiling, 15,000 rupees, in paise. */
    public static final long DEFAULT_AFA_CAP_PAISE = 1_500_000L;

    private final long afaCapPaise;

    @Autowired
    public MandatePolicy(BillingGatewayProperties properties) {
        this(properties.getAfaCapPaise());
    }

    public MandatePolicy(long afaCapPaise) {
        this.afaCapPaise = afaCapPaise;
    }

    public long afaCapPaise() {
        return afaCapPaise;
    }

    /** Rupees to paise, the unit every Indian provider bills in. */
    public static long toPaise(BigDecimal rupees) {
        if (rupees == null) {
            return 0L;
        }
        return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The amount a plan debits per cycle on the given interval, in paise. */
    public static long cycleAmountPaise(Plan plan, BillingPeriod interval) {
        BigDecimal price = interval == BillingPeriod.ANNUAL ? plan.getAnnualPrice() : plan.getMonthlyPrice();
        return toPaise(price);
    }

    /** Whether a debit of this size needs the payer to authenticate on every cycle. */
    public boolean requiresPerChargeAfa(long amountPaise) {
        return amountPaise > afaCapPaise;
    }

    /**
     * Checks a create-subscription request against the rules.
     *
     * @param cmd The request.
     * @param cycleAmountPaise What the plan debits per cycle on the requested interval.
     * @throws MandatePolicyViolationException when a rule is broken.
     */
    public void validateCreate(CreateSubscriptionCommand cmd, long cycleAmountPaise) {
        if (cmd.quantity() < 1) {
            throw new MandatePolicyViolationException("Subscription quantity must be at least 1");
        }
        if (cmd.trialDays() < 0) {
            throw new MandatePolicyViolationException("Trial days cannot be negative");
        }
        if (cycleAmountPaise <= 0) {
            throw new MandatePolicyViolationException(
                    "Plan '" + cmd.planCode() + "' has no price for the " + cmd.interval() + " interval");
        }
        requireNotifyChannel(cmd.notifyInfo());
        long perCycle = perCycle(cmd.planCode(), cycleAmountPaise, cmd.quantity());
        if (requiresPerChargeAfa(perCycle) && !cmd.acceptPerChargeAfa()) {
            throw new MandatePolicyViolationException(
                    "Plan '" + cmd.planCode() + "' debits " + perCycle + " paise per cycle, above the RBI cap of "
                            + afaCapPaise + " paise; each payment will need the payer to authenticate, "
                            + "which the buyer must accept explicitly");
        }
    }

    /**
     * Checks a plan change against the rules: the new cycle amount is subject to the same cap
     * and acceptance as a new subscription. The notification channel is already on file.
     *
     * @param cmd The request.
     * @param cycleAmountPaise What the target plan debits per cycle on the requested interval.
     * @throws MandatePolicyViolationException when a rule is broken.
     */
    public void validateChange(ChangePlanCommand cmd, long cycleAmountPaise) {
        if (cycleAmountPaise <= 0) {
            throw new MandatePolicyViolationException(
                    "Plan '" + cmd.newPlanCode() + "' has no price for the " + cmd.interval() + " interval");
        }
        long perCycle = perCycle(cmd.newPlanCode(), cycleAmountPaise, Math.max(1, cmd.quantity()));
        if (requiresPerChargeAfa(perCycle) && !cmd.acceptPerChargeAfa()) {
            throw new MandatePolicyViolationException(
                    "Plan '" + cmd.newPlanCode() + "' debits " + perCycle + " paise per cycle, above the RBI cap of "
                            + afaCapPaise + " paise; each payment will need the payer to authenticate, "
                            + "which the buyer must accept explicitly");
        }
    }

    /** Amount times quantity, refusing rather than wrapping on overflow. */
    private static long perCycle(String planCode, long cycleAmountPaise, int quantity) {
        try {
            return Math.multiplyExact(cycleAmountPaise, quantity);
        } catch (ArithmeticException e) {
            throw new MandatePolicyViolationException(
                    "Plan '" + planCode + "' times quantity " + quantity + " overflows the cycle amount");
        }
    }

    /**
     * Checks a mandate registration request against the rules.
     *
     * @param cmd The request.
     * @throws MandatePolicyViolationException when a rule is broken.
     */
    public void validateMandate(InitiateMandateCommand cmd) {
        if (cmd.method() == null || cmd.method() == MandateMethod.UNKNOWN) {
            throw new MandatePolicyViolationException("A mandate needs a registration method");
        }
        if (cmd.maxAmountPaise() <= 0) {
            throw new MandatePolicyViolationException("A mandate needs a positive maximum amount");
        }
        requireNotifyChannel(cmd.notifyInfo());
    }

    private static void requireNotifyChannel(NotifyInfo notify) {
        if (notify == null || !notify.hasChannel()) {
            throw new MandatePolicyViolationException(
                    "The payer must be reachable for the 24 hour pre-debit notification; supply an email or a phone number");
        }
    }
}
