package org.tornotron.echno_backend.material.lowstock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the reorder sweep behaves. Bound from the {@code inventory.reorder-sweep.*} block in
 * application.yml.
 *
 * <p>Off by default, in the shape {@code ComplianceSweepProperties} established. The mechanism is
 * settled and the schedule is a decision somebody has to make with a real inbox in front of them,
 * so turning it on is configuration rather than a build.
 */
@Data
@Component
@ConfigurationProperties(prefix = "inventory.reorder-sweep")
public class LowStockSweepProperties {

    /**
     * Master switch. False, so the bean does not exist until somebody turns it on.
     *
     * <p>Turning it on costs no money, unlike the compliance sweep, but it does put notifications
     * in people's inboxes, and an alert nobody asked for is muted once and then useless for ever.
     */
    private boolean enabled = false;

    /**
     * When a pass runs, as a Spring cron expression.
     *
     * <p>Half past three rather than two, which is when the compliance sweep runs. The two do not
     * contend for anything expensive, since this one makes no model calls, but they share a
     * database and a scheduler pool and there is no reason to put both on the same minute.
     *
     * <p>Before the working day rather than during it: what the storekeeper wants is the morning's
     * list, and the crossings that matter happened yesterday.
     */
    private String cron = "0 30 3 * * *";

    /**
     * Time zone the cron is read in. UTC, so the schedule does not move with a host's zone and
     * does not shift twice a year.
     */
    private String zone = "UTC";

    /**
     * How many stock-holding projects one pass looks at, across every tenant.
     *
     * <p>This bounds the scan. The candidate query has no organization filter by design, and an
     * unbounded read of every project holding stock in the database is fine at today's handful and
     * is not fine later. The ordering is stable rather than rotating, so this has to stay above
     * the real number of such projects; see {@code LowStockRepository#findProjectsHoldingStock}.
     */
    private int projectScanLimit = 2000;

    /**
     * How many low materials one pass reports on per project.
     *
     * <p>A project genuinely short of two hundred things has a procurement problem rather than a
     * notification problem, and telling one person about all two hundred in one night is how a
     * category gets muted. Materials come back most depleted first, so a capped project still
     * reports the worst of it, and the rest arrive on the passes after.
     */
    private int materialsPerProject = 25;

    /**
     * How many materials one pass may raise notifications for in total, across every tenant.
     *
     * <p>The second bound, on noise rather than on reads. It is counted from the latch table
     * rather than in memory so that N replicas share one budget instead of having one each. That
     * makes it a bound and not a lock: two replicas starting a pass in the same second can both
     * send before either sees the other, so the real ceiling is this plus roughly one material
     * per replica.
     */
    private int maxNotificationsPerRun = 200;

    /**
     * How far above its reorder level a material has to recover before it is worth reporting on
     * again, as a fraction of that level.
     *
     * <p>This is the whole answer to a material that hovers. Clearing the latch the moment stock
     * ticks one unit above the level would mean a material oscillating around the boundary is
     * reported again on the next pass, and again the pass after, which is the nightly re-raise the
     * latch exists to prevent, arrived at by a different route. At the default a material with a
     * level of 30 has to reach above 33 before a later dip counts as a new crossing.
     *
     * <p>A level of zero needs no special case and gets none: zero times anything is zero, so a
     * material whose owner asked to be told when it is gone rearms as soon as it holds anything
     * at all, which is the right reading of what they asked for.
     */
    private double rearmMargin = 0.10;
}
