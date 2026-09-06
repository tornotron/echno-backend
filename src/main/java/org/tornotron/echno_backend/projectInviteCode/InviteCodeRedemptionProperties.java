package org.tornotron.echno_backend.projectInviteCode;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limits on how often an invite code may be offered for redemption. Bound from
 * {@code echno.invite-code.redemption.*}.
 *
 * <p>An invite code is a bearer credential: whoever presents a live value joins the organization
 * it belongs to. Redemption looks the value up with no organization qualifier, and it cannot
 * carry one, because the person redeeming has no tenant yet. What is left between a value and
 * membership is that the value has to be known, and that only holds while the number of values
 * that may be offered is bounded. These are the bound.
 *
 * <p>They reduce a rate; they do not make a code strong. A code whose shape leaves it worth
 * guessing is still worth guessing more slowly. Widening the code is the change that removes the
 * question, and it is a contract change across the published API, {@code echno-core} and the
 * mobile client, so it is tracked separately.
 */
@Data
@Component
@ConfigurationProperties(prefix = "echno.invite-code.redemption")
public class InviteCodeRedemptionProperties {

    /**
     * Attempts one caller may make in a window. Refunded on success, so an ordinary redemption
     * spends nothing and this is in practice a budget for wrong codes.
     *
     * <p>Ten leaves room for someone mistyping a code they were legitimately given several times
     * over, which is the only way a real person reaches this at all. Zero disables the per-caller
     * limit.
     */
    private int attemptsPerCaller = 10;

    /**
     * Attempts the whole deployment may make in a window, across every caller.
     *
     * <p>The per-caller limit alone is only as strong as the cost of holding another account, and
     * where self-registration is open that cost is close to nothing. This is the ceiling that
     * does not move when someone brings more accounts.
     *
     * <p>It buys that at a price worth stating plainly: someone who burns it delays everyone
     * else's redemptions until it refills. That is why it is set well above any believable volume
     * of genuine wrong codes rather than tight, and why it is configurable per deployment. Zero
     * disables it, which is the right setting only where something upstream is doing the same job.
     */
    private int attemptsPerDeployment = 500;

    /** The window both allowances refill over. */
    private Duration window = Duration.ofHours(1);
}
