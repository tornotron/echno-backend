package org.tornotron.echno_backend.storageLocation;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.project.Project;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pins the decision about what a storage location with no project means, so that changing
 * it is a deliberate edit to one file rather than a drift between the paths that book
 * stock. The rule: no project means an organisation-level store, usable from every
 * project; a named project means that project alone. The web client holds the same rule
 * in {@code features/material-consumptions/storage-location-scope.ts}.
 *
 * <p>The stock-adjustment exception is pinned here too: correcting a balance that already
 * sits on a location owned by another project is allowed, and inventing one is not.
 */
class StorageLocationScopeTest {

    private static final Long PROJECT = 3L;
    private static final Long OTHER_PROJECT = 9L;

    private StorageLocation location(Long id, Long projectId) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        if (projectId != null) {
            Project project = new Project();
            project.setId(projectId);
            location.setProject(project);
        }
        return location;
    }

    @Test
    void aLocationWithNoProjectIsOrganisationLevelAndUsableFromEveryProject() {
        StorageLocation central = location(14L, null);

        assertThat(StorageLocationScope.isUsableFromProject(central, PROJECT)).isTrue();
        assertThat(StorageLocationScope.isUsableFromProject(central, OTHER_PROJECT)).isTrue();
        assertThatCode(() -> StorageLocationScope.requireUsableFromProject(central, PROJECT))
                .doesNotThrowAnyException();
    }

    @Test
    void aLocationIsUsableFromTheProjectItBelongsTo() {
        StorageLocation site = location(7L, PROJECT);

        assertThat(StorageLocationScope.isUsableFromProject(site, PROJECT)).isTrue();
        assertThatCode(() -> StorageLocationScope.requireUsableFromProject(site, PROJECT))
                .doesNotThrowAnyException();
    }

    @Test
    void aLocationOnAnotherProjectIsRefusedAndTheMessageNamesBothProjects() {
        StorageLocation site = location(7L, OTHER_PROJECT);

        assertThat(StorageLocationScope.isUsableFromProject(site, PROJECT)).isFalse();
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> StorageLocationScope.requireUsableFromProject(site, PROJECT))
                .withMessageContaining("Storage location with ID 7")
                .withMessageContaining("belongs to project with ID 9")
                .withMessageContaining("project with ID 3");
    }

    @Test
    void aBalanceAlreadySittingOnAnotherProjectsLocationCanBeCorrected() {
        StorageLocation site = location(7L, OTHER_PROJECT);

        assertThatCode(() -> StorageLocationScope
                .requireUsableForBalanceCorrection(site, PROJECT, () -> true))
                .doesNotThrowAnyException();
    }

    @Test
    void anAdjustmentCannotInventABalanceOnAnotherProjectsLocation() {
        StorageLocation site = location(7L, OTHER_PROJECT);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> StorageLocationScope
                        .requireUsableForBalanceCorrection(site, PROJECT, () -> false))
                .withMessageContaining("Storage location with ID 7")
                .withMessageContaining("belongs to project with ID 9")
                .withMessageContaining("holds no balance")
                .withMessageContaining("cannot create one");
    }

    @Test
    void aLocationTheStrictRuleAlreadyAllowsIsNotLookedUpAtAll() {
        StorageLocation site = location(7L, PROJECT);
        AtomicBoolean lookedUp = new AtomicBoolean(false);

        assertThatCode(() -> StorageLocationScope.requireUsableForBalanceCorrection(site, PROJECT, () -> {
            lookedUp.set(true);
            return false;
        })).doesNotThrowAnyException();

        assertThat(lookedUp).isFalse();
    }

    @Test
    void noLocationAtAllIsNotSomethingToRefuse() {
        assertThat(StorageLocationScope.isUsableFromProject(null, PROJECT)).isTrue();
        assertThatCode(() -> StorageLocationScope.requireUsableFromProject(null, PROJECT))
                .doesNotThrowAnyException();
    }
}
