package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.project.ProjectMembershipChanged;

/** A change of project membership drops that person's cached facts. */
class AccessFactsEvictionListenerTest {

    @Test
    void joiningOrLeavingAProjectEvictsThatPerson() {
        CachedAccessFactsSource facts = mock(CachedAccessFactsSource.class);

        new AccessFactsEvictionListener(facts).onProjectMembershipChanged(new ProjectMembershipChanged(7L, 1L));

        verify(facts).evictAfterCommit(7L, 1L);
    }
}
