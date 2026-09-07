package org.tornotron.echno_backend.material.lowstock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.NotificationDraft;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tells whoever can act on it that a material has crossed its reorder level on a project.
 *
 * <h2>What it does, and the one thing it deliberately does not</h2>
 *
 * <p>It notifies. It does not raise a purchase order, an indent, or any other procurement
 * document, and this is the decision rather than an omission. Reorder levels are set and the
 * comparison against them is built ({@code GET /api/v1/materials/low-stock}, #652); what was
 * missing is that the answer only existed while somebody was looking at it. A job that raised
 * paperwork instead would be creating documents that carry approval workflows and money, nightly,
 * on nobody's instruction. Telling somebody is reversible by ignoring it. Raising an indent is
 * not.
 *
 * <h2>Who is told</h2>
 *
 * <p>The storekeepers assigned to the project, and only if there are none, the project managers
 * assigned to it. {@code OrgRole.STORE_KEEPER} exists as of #650 and is exactly the person who
 * books the goods receipt that answers this, which is the whole point of choosing a recipient: the
 * notification goes to somebody who can do something about it in the same application.
 *
 * <p>Before that role existed the only available recipient was an organization administrator, and
 * an administrator getting last night's shortfalls from twelve sites they do not run is the
 * definition of the alert that gets muted in a week. So an administrator is <em>not</em> a
 * fallback here. A project with nobody assigned in either role is counted and logged, and no
 * notification is sent and no latch is written, so the first crossing still reaches whoever is
 * eventually assigned rather than having been silently spent on nobody.
 *
 * <p>Org roles are read from the {@code Employee} row, where Keycloak's groups are mirrored, and
 * not from Keycloak. The admin client can list a user's groups but has no reverse lookup from a
 * subgroup to its members, so "who holds this role" is a question only the database can answer.
 *
 * <h2>Project scope, not organization and not location</h2>
 *
 * <p>The organization total is the wrong question and hides the answer: on staging {@code TNT
 * Steel} totals 60 against a level of 30 while holding 1, 18, 1, 7 and 1 unit at five separate
 * sites, so an organization-scope sweep would find nothing wrong at any of them. Storage-location
 * scope is the noisiest reading, one event per shelf, and it has no recipient of its own, since
 * the person who would be told about a location is the storekeeper on its project. The project is
 * where the shortfall is real and where somebody is responsible for it.
 *
 * <h2>What counts as low, and what an unset level means</h2>
 *
 * <p>The comparison is not restated here. It is {@code LowStockRepository#findLowStockForProject},
 * the same read the endpoint answers from, so the job and the endpoint cannot disagree about what
 * is low. What that means, in full:
 *
 * <ul>
 *   <li><b>At or below, not below.</b> A reorder level is the level at which you reorder, so
 *       sitting exactly on it is the moment to act.</li>
 *   <li><b>A null level is never reported, at any scope.</b> Nobody has said what low means for
 *       that material, and a job that guessed would be inventing a threshold and then waking
 *       somebody up about it. This is the case most easily conflated with the next one and it is
 *       a different state entirely.</li>
 *   <li><b>A level of zero is a set level.</b> Somebody drew the line at nothing on hand, which is
 *       a decision and reads as "tell me when it is gone". It is reported once the material holds
 *       nothing, and never before.</li>
 *   <li><b>Only materials the project actually carries.</b> Project scope inner-joins the stock
 *       rows, so the catalogue is not reported as absent from every project that has never held
 *       it.</li>
 * </ul>
 *
 * <h2>Crossing, not being below</h2>
 *
 * <p>A material is reported when it crosses its level, once, and not again while it stays down.
 * Being below is a standing fact and the endpoint answers it on demand; re-sending it nightly is
 * how the whole category gets muted, and then the one crossing that mattered is lost with the
 * rest. {@link MaterialReorderAlert} holds the latch that makes this possible, keyed on the
 * material and the project together, and its unique constraint is also what stops two replicas
 * both sending.
 *
 * <p>Two things end a latch, and one of them is the answer to a material that hovers.
 *
 * <ul>
 *   <li><b>Recovery, with a margin.</b> The latch clears once the project holds more than the
 *       level plus {@code rearmMargin} of it, not the moment it ticks one unit above. Without the
 *       margin a material oscillating on the boundary would be re-reported every pass, which is
 *       the nightly re-raise the latch exists to prevent, reached by another route.</li>
 *   <li><b>The level moving.</b> A material reported at a level of 30 and now measured against 100
 *       is below a different line than the one anybody was told about. That is a new event. The
 *       alternative is that raising a level latches a material permanently at the moment it
 *       becomes badly short.</li>
 * </ul>
 *
 * <p>A material that stays down for a month is therefore reported once. That is intended. The
 * notification says something changed; the standing list of what is short is the endpoint's job
 * and it is one click away.
 *
 * <h2>Tenants</h2>
 *
 * <p>The pass is {@link WithoutTenant} because it belongs to no organization, and everything it
 * reads at that level is a pair of ids. Every read and write that touches a tenant's rows happens
 * inside {@link TenantScopedJobRunner#runForTenant}, which refuses a null organization id. That is
 * not belt and braces: both isolation mechanisms fail <em>open</em> on a missing organization id
 * and {@code UnscopedAccessGuard} defaults to warning rather than denying, so a scheduled job that
 * forgot its scope would read every tenant's rows and look perfectly healthy doing it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "inventory.reorder-sweep.enabled", havingValue = "true")
public class LowStockSweep {

    private final LowStockRepository lowStockRepository;
    private final MaterialReorderAlertRepository alertRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final MaterialRepository materialRepository;
    private final NotificationService notificationService;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final LowStockSweepProperties properties;

    /**
     * One pass.
     *
     * <p>Nothing here throws. Spring stops rescheduling a {@code @Scheduled} method that throws,
     * so a single bad night would silently end the schedule, and a sweep that quietly stopped
     * sweeping is a worse failure than the one it exists to fix.
     */
    @Scheduled(cron = "${inventory.reorder-sweep.cron:0 30 3 * * *}",
            zone = "${inventory.reorder-sweep.zone:UTC}")
    @WithoutTenant("The reorder sweep belongs to no organization: it exists to find which "
            + "organizations have projects holding stock, reads two ids and nothing else at that "
            + "level, and establishes a tenant per project before reading or writing anything "
            + "belonging to one")
    public void sweep() {
        try {
            runPass();
        } catch (Exception e) {
            log.error("Low stock sweep pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * The pass itself. Package-private so a test can run it and assert on what it did without
     * waiting on a cron.
     */
    void runPass() {
        LocalDateTime passStartedAt = LocalDateTime.now();

        List<StockedProject> candidates = lowStockRepository.findProjectsHoldingStock(
                PageRequest.of(0, Math.max(1, properties.getProjectScanLimit())));
        if (candidates.isEmpty()) {
            log.debug("Low stock sweep found no project holding stock; nothing to compare");
            return;
        }

        int cap = Math.max(0, properties.getMaxNotificationsPerRun());
        int raised = 0;
        int stillLow = 0;
        int recovered = 0;
        int unaddressed = 0;
        boolean cappedOut = false;

        for (StockedProject candidate : candidates) {
            if (!withinBudget(cap, raised, passStartedAt)) {
                cappedOut = true;
                break;
            }
            try {
                ProjectOutcome outcome = tenantScopedJobRunner.callForTenant(
                        candidate.organizationId(), () -> sweepProject(candidate));
                raised += outcome.raised();
                stillLow += outcome.stillLow();
                recovered += outcome.recovered();
                unaddressed += outcome.unaddressed();
            } catch (Exception e) {
                // One project's night must not cost every other project theirs.
                log.error("Low stock sweep could not sweep project {} in organization {}: {}",
                        candidate.projectId(), candidate.organizationId(), e.getMessage(), e);
            }
        }

        if (cappedOut) {
            log.info("Low stock sweep stopped at its per-run cap of {} material(s); the rest are "
                    + "picked up by the next pass", cap);
        }
        log.info("Low stock sweep looked at {} project(s): reported {} newly at or below level, "
                        + "{} already reported and still down, {} recovered, {} with nobody to tell",
                candidates.size(), raised, stillLow, recovered, unaddressed);
    }

    /** What one project's pass did. */
    private record ProjectOutcome(int raised, int stillLow, int recovered, int unaddressed) {
    }

    /**
     * One project, under its own tenant.
     *
     * <p>The two halves are the low list and the latch list, and the second half is about the
     * latches the first half did not match: a material that was reported and has since dropped out
     * of the low answer is the one that might have recovered.
     */
    private ProjectOutcome sweepProject(StockedProject candidate) {
        Long orgId = candidate.organizationId();
        Long projectId = candidate.projectId();

        List<LowStockRow> low = lowStockRepository.findLowStockForProject(orgId, projectId,
                PageRequest.of(0, Math.max(1, properties.getMaterialsPerProject()))).getContent();

        Map<Long, MaterialReorderAlert> latched = new LinkedHashMap<>();
        for (MaterialReorderAlert alert : alertRepository
                .findByOrganization_IdAndProject_Id(orgId, projectId)) {
            latched.put(alert.getMaterial().getId(), alert);
        }

        int raised = 0;
        int stillLow = 0;
        int unaddressed = 0;
        List<Employee> recipients = null;

        for (LowStockRow row : low) {
            MaterialReorderAlert existing = latched.remove(row.materialId());
            if (existing != null && sameLevel(existing.getNotifiedLevel(), row.reorderLevel())) {
                // Already reported against this level, and still down. Nothing has changed, so
                // there is nothing to say.
                stillLow++;
                continue;
            }

            if (recipients == null) {
                recipients = recipientsFor(orgId, projectId);
            }
            if (recipients.isEmpty()) {
                // No latch is written. The crossing has not been reported, so it must still be
                // reportable once somebody is assigned to the project.
                unaddressed++;
                continue;
            }

            if (raise(orgId, projectId, row, recipients, existing)) {
                raised++;
            }
        }

        int recovered = clearRecovered(orgId, projectId, latched);
        return new ProjectOutcome(raised, stillLow, recovered, unaddressed);
    }

    /**
     * Sends one material's notification and latches it.
     *
     * <p>The latch is written after the notifications rather than before, so a failure to save it
     * cannot leave a material latched that nobody was told about. The other order of the same
     * failure, notifications sent and no latch, costs a repeat on the next pass, which is the
     * cheaper of the two mistakes.
     *
     * @return Whether anything was sent. A losing race is not a failure and is not counted.
     */
    private boolean raise(Long orgId, Long projectId, LowStockRow row,
                          List<Employee> recipients, MaterialReorderAlert existing) {
        Optional<Project> project = projectRepository.findById(projectId);
        String projectName = project.map(Project::getProjectName).orElse("project " + projectId);

        NotificationDraft draft = new NotificationDraft(
                NotificationType.MATERIAL_LOW_STOCK,
                title(row),
                message(row, projectName),
                "MATERIAL",
                row.materialId(),
                "/materials/" + row.materialId());

        try {
            notificationService.deliverToAll(recipients, draft);
            latch(orgId, projectId, row, recipients.size(), existing);
        } catch (DataIntegrityViolationException e) {
            // Another replica latched the same material in the same second. Its notifications
            // went out; ours may have too, and one duplicate is the documented cost of not
            // running a leader election for a nightly job.
            log.debug("Low stock sweep lost the latch race for material {} on project {}: {}",
                    row.materialId(), projectId, e.getMessage());
            return false;
        }
        log.info("Low stock sweep reported material {} on project {} in organization {} to {} "
                + "recipient(s)", row.materialId(), projectId, orgId, recipients.size());
        return true;
    }

    /** Writes or refreshes the latch for one material on one project. */
    private void latch(Long orgId, Long projectId, LowStockRow row, int recipientCount,
                       MaterialReorderAlert existing) {
        MaterialReorderAlert alert = existing != null ? existing : new MaterialReorderAlert();
        if (existing == null) {
            Organization organization = organizationRepository.getReferenceById(orgId);
            Project project = projectRepository.getReferenceById(projectId);
            Material material = materialRepository.getReferenceById(row.materialId());
            alert.setOrganization(organization);
            alert.setProject(project);
            alert.setMaterial(material);
        }
        alert.setNotifiedLevel(row.reorderLevel());
        alert.setNotifiedQuantity(row.currentStock() == null ? 0.0 : row.currentStock());
        alert.setRecipientCount(recipientCount);
        alert.setNotifiedAt(LocalDateTime.now());
        alertRepository.saveAndFlush(alert);
    }

    /**
     * Deletes the latches whose material has recovered, and returns how many.
     *
     * <p>Every latch handed here is for a material the project's low-stock answer no longer
     * contains, which is one of three things: it has climbed above its level, its level has been
     * cleared, or the material has been removed from the catalogue. The first is the only one that
     * needs a margin.
     *
     * <p>A material still at or below the rearm line keeps its latch. That includes a material
     * whose stock rows on the project are all gone, which reads as nothing on hand: it holds
     * nothing there, so it has not recovered, and re-reporting it would say nothing new.
     */
    private int clearRecovered(Long orgId, Long projectId, Map<Long, MaterialReorderAlert> latched) {
        if (latched.isEmpty()) {
            return 0;
        }

        Map<Long, ProjectMaterialStock> now = new HashMap<>();
        for (ProjectMaterialStock stock : lowStockRepository
                .findProjectStockForMaterials(orgId, projectId, latched.keySet())) {
            now.put(stock.materialId(), stock);
        }

        List<MaterialReorderAlert> clear = new ArrayList<>();
        for (Map.Entry<Long, MaterialReorderAlert> entry : latched.entrySet()) {
            ProjectMaterialStock stock = now.get(entry.getKey());
            if (stock == null) {
                // The material is gone from the catalogue. There is nothing left to report on.
                clear.add(entry.getValue());
                continue;
            }
            if (stock.reorderLevel() == null) {
                // The level has been cleared. Nobody is saying what low means for this material
                // any more, so there is no line for it to be below.
                clear.add(entry.getValue());
                continue;
            }
            if (hasRearmed(stock.currentStock(), stock.reorderLevel())) {
                clear.add(entry.getValue());
            }
        }

        if (!clear.isEmpty()) {
            alertRepository.deleteAll(clear);
        }
        return clear.size();
    }

    /**
     * Whether stock has come back far enough above the level for a later dip to count as a new
     * crossing.
     *
     * <p>Strictly above level plus the margin. A level of zero rearms at anything above nothing,
     * which needs no special case: the margin is a fraction of the level and a fraction of zero is
     * zero.
     */
    private boolean hasRearmed(Double quantity, Double level) {
        double onHand = quantity == null ? 0.0 : quantity;
        double margin = Math.max(0.0, properties.getRearmMargin());
        return onHand > level * (1.0 + margin);
    }

    /**
     * Whether the level a material was reported against is the level in force now.
     *
     * <p>Compared as doubles because that is what both are, with the null case answered rather
     * than assumed: a latch always carries a level, and a row in the low answer always carries
     * one, so a null on either side is a state neither is supposed to reach and is treated as a
     * change rather than as equality.
     */
    private boolean sameLevel(Double latchedLevel, Double currentLevel) {
        if (latchedLevel == null || currentLevel == null) {
            return false;
        }
        return latchedLevel.compareTo(currentLevel) == 0;
    }

    /**
     * Who to tell about a shortfall on one project.
     *
     * <p>Storekeepers first, project managers only if the project has none. An organization
     * administrator is deliberately not the third rung: see the class comment.
     */
    private List<Employee> recipientsFor(Long orgId, Long projectId) {
        List<Employee> keepers = employeeRepository.findByProjectAndOrgRole(
                orgId, projectId, OrgRole.STORE_KEEPER);
        if (!keepers.isEmpty()) {
            return keepers;
        }
        List<Employee> managers = employeeRepository.findByProjectAndOrgRole(
                orgId, projectId, OrgRole.PROJECT_MANAGER);
        if (managers.isEmpty()) {
            log.info("Low stock sweep found nobody to tell about project {} in organization {}: "
                    + "no storekeeper and no project manager is assigned to it", projectId, orgId);
        }
        return managers;
    }

    /**
     * Whether this pass may still report, counting what every replica has reported rather than
     * only what this one has.
     */
    private boolean withinBudget(int cap, int raisedHere, LocalDateTime passStartedAt) {
        if (raisedHere >= cap) {
            return false;
        }
        long raisedAnywhere = alertRepository.countByNotifiedAtGreaterThanEqual(passStartedAt);
        return Math.max(raisedHere, raisedAnywhere) < cap;
    }

    /** The heading. Kept inside 200 characters, which is what the column holds. */
    private String title(LowStockRow row) {
        return truncate("Low stock: " + row.materialName(), 200);
    }

    /** The body. Kept inside 1000 characters, which is what the column holds. */
    private String message(LowStockRow row, String projectName) {
        StringBuilder text = new StringBuilder()
                .append(row.materialName())
                .append(" is down to ")
                .append(number(row.currentStock()));
        if (row.unit() != null && !row.unit().isBlank()) {
            text.append(' ').append(row.unit());
        }
        text.append(" on ").append(projectName)
                .append(", at or below its reorder level of ")
                .append(number(row.reorderLevel()))
                .append('.');
        if (row.moq() != null && row.moq() > 0) {
            text.append(" The minimum order quantity is ").append(number(row.moq())).append('.');
        }
        return truncate(text.toString(), 1000);
    }

    /**
     * A quantity as a person would write it, so a whole number reads as {@code 60} rather than
     * {@code 60.0}.
     */
    private String number(Double value) {
        if (value == null) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Cuts text to what the column holds, rather than letting the insert fail on a long name. */
    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
