package org.tornotron.echno_backend.compliance.sweep;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the catch-up sweep behaves. Bound from the {@code compliance.sweep.*} block in
 * application.yml.
 *
 * <p>It is off by default, which is a deliberate choice rather than caution about the code.
 * Three questions here are product decisions and none of them can be answered from the
 * codebase: which changes to the catalogue should count as worth re-assessing a project for,
 * how often the sweep should run, and whether a tenant should be able to decline it. The
 * mechanism is built and the answers are configuration, so whoever settles them turns it on
 * without a build.
 */
@Data
@Component
@ConfigurationProperties(prefix = "compliance.sweep")
public class ComplianceSweepProperties {

    /**
     * Master switch. False, so nothing runs until someone decides it should.
     *
     * <p>Turning it on is safe in the sense that the queue paces the work and the generation
     * path is idempotent, but it does spend money on inference for every project it finds
     * stale, and how much depends entirely on what the catalogue looks like by then.
     */
    private boolean enabled = false;

    /**
     * When the pass runs, as a Spring cron expression. Two in the morning by default, because
     * the work is inference against a shared endpoint and the cheapest time to contend for it
     * is when nobody is using the application.
     */
    private String cron = "0 0 2 * * *";

    /**
     * Time zone the cron is read in. UTC rather than the server's zone, so the schedule does
     * not move when a host's zone is set differently, and does not shift twice a year.
     */
    private String zone = "UTC";

    /**
     * How many approved projects one pass looks at.
     *
     * <p>This bounds the scan, not the spend. It exists because the query runs across every
     * tenant with no organization filter, and an unbounded scan of every approved project in
     * the database is the kind of query that is fine at today's 29 projects and is not fine
     * later. Candidates come back least-recently-attempted first, so a pass that hits this
     * ceiling still makes deterministic progress and the next one continues from where it left
     * off, and a project that fails every night sinks down the list instead of holding the
     * front of it.
     */
    private int scanLimit = 500;

    /**
     * How many jobs one pass may enqueue.
     *
     * <p>The real pacing is the queue, not this: {@code compliance.job.max-in-flight} decides
     * how many runs are ever in flight at once, so enqueueing 200 jobs does not make 200
     * simultaneous calls to the inference endpoint, it makes a queue two deep that drains over
     * the night. This cap is the second bound, on total spend rather than on concurrency. At
     * the measured 1.7 seconds and 57 completion tokens per rule, 25 projects over a 21-rule
     * catalogue is about nine minutes of wall clock at the default two in flight, and it is
     * the number to raise once someone has looked at a real bill.
     *
     * <p>It is counted from the job table rather than in memory, so it is one budget shared by
     * every replica rather than one each. That is a bound and not a lock: two replicas that
     * start a pass in the same second can both submit before either sees the other, so the
     * real ceiling is this number plus roughly one project per replica.
     */
    private int maxProjectsPerRun = 25;
}
