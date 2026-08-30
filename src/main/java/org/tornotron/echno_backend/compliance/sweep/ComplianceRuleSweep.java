package org.tornotron.echno_backend.compliance.sweep;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.IndianStateResolver;
import org.tornotron.echno_backend.compliance.Jurisdiction;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobRepository;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobService;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds approved projects that were never assessed against the compliance rules now in force,
 * and queues a generation run for each.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Compliance is generated when a project is approved, and only then. That is fine for a
 * project approved into a catalogue that already covers it, and silently wrong for every other
 * case. A project approved before its jurisdiction had any rules gets nothing and is never
 * revisited. A project approved while its state was unset is refused at accept time, correctly,
 * and nothing retries it once the state is filled in. And the moment a curated catalogue lands,
 * every project approved before it is permanently missing compliances that nobody knows are
 * missing, which is discovered by someone noticing an empty list.
 *
 * <h2>What makes a project stale</h2>
 *
 * <p>Two comparisons, and both have to say the project is covered before it is left alone.
 * The newest {@code effectiveFrom} among the active rules for the project's jurisdiction,
 * against the moment the last successful run for that project <em>started</em>; and the
 * jurisdiction that run was accepted for, against the one the project is in now. Never
 * assessed is stale by definition.
 *
 * <p>The watermark is the run's start rather than its finish, and that is the difference
 * between a sweep that catches up and one that can lose a rule for good. A run snapshots the
 * catalogue once and then spends minutes in the model, so a rule written during those minutes
 * is not in the snapshot and cannot be; but its {@code effectiveFrom} is still earlier than
 * the moment that run finished. Watermarked on the finish, the project reads as covering a
 * rule nothing ever assessed it against, on that pass and on every pass after it, which is
 * precisely the miss this class exists to prevent. Watermarked on the start, the run vouches
 * only for what was in force when it began. It over-triggers by at most one run per project
 * and that run creates nothing, because generation is idempotent per rule.
 *
 * <p>The jurisdiction is compared for the same kind of reason. A project assessed under Tamil
 * Nadu and later corrected to Kerala has an assessment that covers none of the rules it is now
 * subject to, and if Kerala's rules predate that assessment the timestamps alone say it is up
 * to date. Its compliance list would then be empty for ever.
 *
 * <p>One gap is left open knowingly. A rule <em>back-dated</em> below a project's watermark is
 * invisible to this whenever it is written, in flight or not, because {@code effectiveFrom} is
 * the only input and the sweep cannot distinguish a rule that was always there from one that
 * appeared today claiming it was. That is a property of keying on the curated date rather than
 * on {@code createdAt}, which is a deliberate choice made in the design (see below), and the
 * remedy is a curatorial one: date a rule from when it starts applying to projects, not from
 * whatever historical date it was published.
 *
 * <p>{@code effectiveFrom} rather than {@code updatedAt} is the entire design of this. The two
 * are easy to conflate and the difference is the difference between a sweep that costs almost
 * nothing and one that re-assesses the world: {@code updatedAt} moves when someone fixes a
 * typo, and it moves on every row of a bulk re-seed whose content did not change, so a sweep
 * keyed on it would spend a model call per approved project per tenant to re-derive answers
 * that did not change. {@code effectiveFrom} moves only when a curator says the rule is
 * materially different. See {@code ComplianceRule#effectiveFrom}.
 *
 * <h2>It queues, it does not generate</h2>
 *
 * <p>Every stale project goes through {@link ComplianceGenerationJobService#submit}, the same
 * entry point the approval listener and the manual endpoint use. That is not tidiness. Calling
 * generation inline from here would put a model call per project on the scheduler thread,
 * outside any lease, with no record that it was attempted, no progress for anyone watching, and
 * nothing to recover it when the replica restarts halfway through, which is the whole of what
 * the job queue was built to fix. Going through the queue also means the one-active-job-per-
 * project index does the obvious right thing: a project with a run already in flight is handed
 * back that run instead of getting a second one.
 *
 * <h2>Pacing</h2>
 *
 * <p>The thundering herd this could be is prevented by the queue rather than by anything here.
 * Enqueueing is an insert; {@code compliance.job.max-in-flight} decides how many runs are ever
 * in flight against the inference endpoint, so a pass that queues 25 jobs produces a queue that
 * drains two at a time, not 25 simultaneous calls to a rate-limited endpoint. On top of that
 * this pass bounds both what it reads ({@code scanLimit}) and what it spends
 * ({@code maxProjectsPerRun}), and takes candidates least-recently-attempted first so a capped
 * pass still makes deterministic progress and the next one continues behind it.
 *
 * <p>Least-recently-<em>attempted</em>, not least-recently-assessed. A failed run counts as
 * neither an assessment nor a reason to stop resubmitting, so ordering on success alone parks
 * a project that fails every night permanently at the front of the list, where twenty-five
 * such projects eat the whole cap and nothing behind them is ever reached. Having been tried
 * is enough to move a project down.
 *
 * <p>The spend cap is read from the job table rather than counted in memory, because
 * {@code @Scheduled} elects no leader: on N replicas this pass runs N times, and a cap held in
 * a local variable is really N caps. Counting the rows created since the pass began makes the
 * budget one that all N replicas share. It is not mutual exclusion, and the honest statement
 * of what it buys is that the overshoot falls from a whole cap per replica to about one
 * project per replica, being what a replica can submit between two counts. Anything tighter
 * than that is a coordination protocol, and none is worth writing for a nightly job whose
 * work is idempotent.
 *
 * <h2>Off by default</h2>
 *
 * <p>{@code compliance.sweep.enabled} is false, so this bean does not exist unless someone
 * turns it on. The mechanism is settled; three things about the policy are not, and none of
 * them is a call this code can make. Whether re-dating a rule is the right way to ask for
 * re-assessment, or whether some narrower notion of "changed" is wanted. How often a pass
 * should run. And whether a tenant should be able to decline it, since the spend is real and
 * lands on whoever owns the inference key.
 *
 * <p>One question is deliberately left answered a particular way, and it is worth being
 * explicit about. A project whose state still cannot be resolved is skipped quietly every pass
 * rather than retried against the model, because there is nothing to retry: the precondition
 * fails before any call is made, so retrying costs nothing and achieves nothing. Skipping it
 * silently for ever would be its own version of this bug, so the pass counts them and logs the
 * count. Surfacing that on the project itself, where somebody would see it, is a UI change and
 * is left for whoever picks that up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "compliance.sweep.enabled", havingValue = "true")
public class ComplianceRuleSweep {

    private final ComplianceRuleRepository ruleRepository;
    private final ComplianceGenerationJobRepository jobRepository;
    private final ComplianceGenerationJobService jobService;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final TransactionRetryTemplate retryTemplate;
    private final ComplianceSweepProperties properties;

    /**
     * One pass.
     *
     * <p>Nothing here throws. A pass that fails must not stop the schedule, because Spring stops
     * rescheduling a {@code @Scheduled} method that throws and a catch-up sweep that quietly
     * stopped catching up is the failure this class exists to remove, one level up.
     */
    @Scheduled(cron = "${compliance.sweep.cron:0 0 2 * * *}", zone = "${compliance.sweep.zone:UTC}")
    @WithoutTenant("The sweep belongs to no organization: it exists to find which organizations "
            + "have projects to re-assess, reads scalars only across tenants, and establishes a "
            + "tenant per project before anything is enqueued")
    public void sweep() {
        try {
            runPass();
        } catch (Exception e) {
            log.error("Compliance sweep pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * The pass itself. Package-private so a test can run it and assert on what it did, without
     * waiting on a cron.
     */
    void runPass() {
        LocalDateTime passStartedAt = LocalDateTime.now();

        Map<String, LocalDateTime> newestByJurisdiction = loadJurisdictionChanges();
        if (newestByJurisdiction.isEmpty()) {
            log.debug("Compliance sweep found no active rules; nothing to compare projects against");
            return;
        }

        List<ComplianceGenerationJobRepository.SweepCandidate> candidates = retryTemplate.execute(
                "ComplianceRuleSweep.findCandidates",
                () -> jobRepository.findSweepCandidates(Math.max(1, properties.getScanLimit())));

        int cap = Math.max(0, properties.getMaxProjectsPerRun());
        int enqueued = 0;
        int alreadyRunning = 0;
        int notAssessable = 0;
        int upToDate = 0;

        for (ComplianceGenerationJobRepository.SweepCandidate candidate : candidates) {
            String jurisdiction = jurisdictionOf(candidate);
            if (jurisdiction == null) {
                // No jurisdiction we could resolve. Generation would refuse this project
                // anyway, so there is nothing to queue.
                notAssessable++;
                continue;
            }

            LocalDateTime newest = newestByJurisdiction.get(jurisdiction);
            if (newest == null) {
                // No active rules registered for it, so again nothing a run could assess.
                notAssessable++;
                continue;
            }

            if (isCovered(candidate, jurisdiction, newest)) {
                upToDate++;
                continue;
            }

            if (!withinBudget(cap, enqueued, passStartedAt)) {
                log.info("Compliance sweep stopped at its per-run cap of {} project(s); the rest "
                        + "are picked up by the next pass", cap);
                break;
            }

            switch (submit(candidate)) {
                case ENQUEUED -> enqueued++;
                case ALREADY_RUNNING -> alreadyRunning++;
                case NOT_ASSESSABLE -> notAssessable++;
            }
        }

        log.info("Compliance sweep looked at {} approved project(s): queued {}, {} already running, "
                        + "{} up to date, {} not assessable yet",
                candidates.size(), enqueued, alreadyRunning, upToDate, notAssessable);
    }

    /**
     * Whether the last successful run for this project already covers everything in force.
     *
     * <p>Both halves have to hold. The run has to have started after the newest rule in the
     * jurisdiction came into force, and it has to have been a run in <em>this</em>
     * jurisdiction. A run whose jurisdiction was not recorded, which is every run from before
     * that column existed, is taken at its timestamp alone rather than treated as a change, so
     * turning this on does not re-assess the entire estate on the first pass.
     *
     * <p>The equality boundary falls on the side of covered: a rule that came into force at
     * the exact instant a run started was in that run's snapshot, since the snapshot is read
     * after the run is claimed. It has to fall one way or the other, and falling this way
     * means a rule and a run written in the same instant do not requeue the project nightly
     * with nothing to add.
     */
    private boolean isCovered(ComplianceGenerationJobRepository.SweepCandidate candidate,
                              String jurisdiction,
                              LocalDateTime newest) {
        LocalDateTime lastAssessed = candidate.getLastAssessedAt();
        if (lastAssessed == null) {
            return false;
        }

        String assessedJurisdiction = candidate.getLastAssessedJurisdiction();
        if (assessedJurisdiction != null && !assessedJurisdiction.equals(jurisdiction)) {
            log.debug("Compliance sweep sees project {} moved from jurisdiction {} to {}; its "
                            + "last assessment covers rules it is no longer subject to",
                    candidate.getProjectId(), assessedJurisdiction, jurisdiction);
            return false;
        }

        return !newest.isAfter(lastAssessed);
    }

    /**
     * Whether this pass may still enqueue, counting what every replica has enqueued rather
     * than only what this one has.
     *
     * <p>The local count is kept in the comparison as well as the shared one so the cap still
     * means something to a caller holding a repository that reports nothing, and because the
     * shared count cannot be lower than what this replica has itself inserted in any case.
     */
    private boolean withinBudget(int cap, int enqueuedHere, LocalDateTime passStartedAt) {
        if (enqueuedHere >= cap) {
            return false;
        }
        long queuedAnywhere = retryTemplate.execute("ComplianceRuleSweep.countCreatedSince",
                () -> jobRepository.countCreatedSince(passStartedAt));
        return Math.max(enqueuedHere, queuedAnywhere) < cap;
    }

    /** What happened to one candidate. */
    private enum Outcome { ENQUEUED, ALREADY_RUNNING, NOT_ASSESSABLE }

    /**
     * Queues a run for one project, under that project's tenant.
     *
     * <p>The two expected exceptions are caught and counted rather than logged as faults. A
     * project with no resolvable state, or none registered for its jurisdiction, is a normal
     * state of affairs on this path and not something an operator should be woken for. Anything
     * else is logged and the pass carries on, because one bad project must not cost every other
     * project its night.
     */
    private Outcome submit(ComplianceGenerationJobRepository.SweepCandidate candidate) {
        Long projectId = candidate.getProjectId();
        Long orgId = candidate.getOrganizationId();
        try {
            ComplianceGenerationJobService.Accepted accepted = tenantScopedJobRunner.callForTenant(
                    orgId, () -> jobService.submit(projectId, orgId));
            if (accepted.created()) {
                log.info("Compliance sweep queued generation for project {} in organization {}",
                        projectId, orgId);
                return Outcome.ENQUEUED;
            }
            return Outcome.ALREADY_RUNNING;
        } catch (InvalidRequestException | ResourceNotFoundException e) {
            log.debug("Compliance sweep skipped project {} in organization {}: {}",
                    projectId, orgId, e.getMessage());
            return Outcome.NOT_ASSESSABLE;
        } catch (Exception e) {
            log.error("Compliance sweep could not queue generation for project {} in organization "
                    + "{}: {}", projectId, orgId, e.getMessage(), e);
            return Outcome.NOT_ASSESSABLE;
        }
    }

    /**
     * The newest moment anything came into force in each jurisdiction, keyed the way the
     * generation query matches rules: state case-insensitively, plus project type.
     */
    private Map<String, LocalDateTime> loadJurisdictionChanges() {
        List<ComplianceRuleRepository.JurisdictionChange> changes = retryTemplate.execute(
                "ComplianceRuleSweep.loadJurisdictionChanges",
                ruleRepository::findNewestEffectiveFromByJurisdiction);

        Map<String, LocalDateTime> byJurisdiction = new HashMap<>();
        for (ComplianceRuleRepository.JurisdictionChange change : changes) {
            if (change.getState() == null || change.getProjectType() == null
                    || change.getNewestEffectiveFrom() == null) {
                continue;
            }
            byJurisdiction.merge(Jurisdiction.key(change.getState(), change.getProjectType()),
                    change.getNewestEffectiveFrom(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return byJurisdiction;
    }

    /**
     * The jurisdiction one project is in now, or null if it has none we can resolve.
     *
     * <p>The state is resolved exactly as generation resolves it, including the free-text
     * address fallback, so the sweep cannot decide a project is stale on a jurisdiction that
     * generation would then refuse to use.
     */
    private String jurisdictionOf(ComplianceGenerationJobRepository.SweepCandidate candidate) {
        String state = IndianStateResolver.forProject(
                candidate.getProjectState(), candidate.getProjectAddress());
        return Jurisdiction.key(state, parseProjectType(candidate.getProjectType()));
    }

    /**
     * The project type as an enum. It arrives as text because the candidate query is native, and
     * a value this build does not know is treated as no type at all rather than crashing the
     * pass over one row.
     */
    private ProjectType parseProjectType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ProjectType.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Compliance sweep does not recognise project type '{}'; skipping that project",
                    value);
            return null;
        }
    }
}
