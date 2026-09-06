package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attachment service used to load an {@link org.tornotron.echno_backend.organization.Organization}
 * by the entity id in the request path, and then throw the result away: all three write paths
 * overwrite the field on the next line with the tenant's own organization. The load could not
 * change the row, and because it ended in {@code orElse(null)} the outcome did not differ by
 * whether the id existed either.
 *
 * <p>It still mattered. {@code Organization} is the tenant root, so it carries neither the
 * {@code orgFilter} nor the fail-closed load listener, and a caller-supplied id reaching an
 * unfiltered {@code findById} is the shape of #687. Anyone auditing this family had to work out
 * that this particular one was inert before moving on. Removing the load removes the question,
 * and removing the repository with it is what keeps the answer from having to be worked out
 * again: there is nothing left in this service that can reach an organization by an id a caller
 * chose.
 *
 * <p>Asserted on the constructor rather than on behaviour because there was no behaviour to
 * assert on. That is the whole reason the line went.
 */
class AttachmentOrganizationLinkTest {

    @Test
    void theServiceCannotReachAnOrganizationByACallerSuppliedId() {
        Constructor<?>[] constructors = AttachmentService.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .as("the organization repository was held for one dead switch arm and nothing else")
                .doesNotContain(OrganizationRepository.class);
    }
}
