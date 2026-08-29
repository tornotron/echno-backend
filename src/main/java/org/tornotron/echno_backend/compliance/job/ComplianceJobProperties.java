package org.tornotron.echno_backend.compliance.job;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How the compliance generation queue behaves. Bound from the {@code compliance.job.*}
 * block in application.yml.
 *
 * <p>Every value here is deliberately overridable per environment, because the two that
 * matter most, {@link #maxInFlight} and {@link #leaseDuration}, are really statements about
 * the inference endpoint and the replica rather than about the application. An environment
 * pointed at a slower endpoint, or one with a per-key concurrency limit, wants a different
 * pair of numbers and should not need a build to get them.
 */
@Data
@Component
@ConfigurationProperties(prefix = "compliance.job")
public class ComplianceJobProperties {

    /**
     * Master switch for the dispatcher. When false the queue still accepts jobs and they
     * stay {@code QUEUED}, which is the right behaviour for a replica that should serve
     * requests without doing background work.
     */
    private boolean enabled = true;

    /**
     * How often each replica looks for work, in milliseconds.
     *
     * <p>Milliseconds rather than a {@link Duration} because this one value is also read
     * straight out of configuration by {@code @Scheduled(fixedDelayString)}, and that
     * annotation accepts a plain number or ISO-8601, not the {@code 2s} shorthand the rest of
     * Spring Boot's binding takes. A property that binds one way here and another way there
     * would be a trap for whoever next tries to change it.
     */
    private long pollIntervalMillis = 2000;

    /**
     * How many jobs one replica runs at once. Two rather than one because a single stuck
     * job should not stop every tenant's queue, and not many more because each in-flight
     * job is an open connection to the inference endpoint for minutes at a time.
     */
    private int maxInFlight = 2;

    /**
     * How far ahead a worker holds its claim. It is pushed forward after every batch, so
     * this only has to outlast one batch by a comfortable margin, not the whole run. Too
     * short and a slow batch gets its job taken away underneath it; too long and a dead
     * replica's work sits unrecovered for that long.
     */
    private Duration leaseDuration = Duration.ofMinutes(5);

    /**
     * Attempts before a job is given up on. Counted at claim time, so a worker that dies
     * without writing anything still uses one up.
     */
    private int maxAttempts = 3;
}
