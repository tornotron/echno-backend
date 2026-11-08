package org.tornotron.echno_backend.common.enums;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/org-scoped-roles.md} honest about {@link OrgRole}.
 *
 * <p>The guide is the only description of this authorization layer anyone reads, and it had drifted
 * to naming four roles where the enum holds seven: the three inspection roles were added and the
 * document was not. A role that exists and is undocumented is worse than an undocumented feature,
 * because the guide reads as complete and someone building an authorization decision on it is
 * working from a list that is missing entries.
 *
 * <p>The check is deliberately a name-presence one rather than an assertion about the prose around
 * each name. Anything stricter would be a second copy of the enum living in a test, which is the
 * problem rather than the fix; anything looser would not have caught the drift this exists to
 * prevent. The document is free to say whatever it likes about a role as long as it says the role
 * is there.
 */
class OrgRoleDocumentationTest {

    private static final Path GUIDE = Path.of(
            System.getProperty("org.scoped.roles.doc", "docs/org-scoped-roles.md"));

    private String guide() throws IOException {
        assertThat(Files.exists(GUIDE))
                .as("the org-scoped roles guide at %s", GUIDE.toAbsolutePath())
                .isTrue();
        return Files.readString(GUIDE, StandardCharsets.UTF_8);
    }

    @Test
    void everyOrgRoleIsNamedInTheGuide() throws IOException {
        String text = guide();

        List<String> missing = new ArrayList<>();
        for (OrgRole role : OrgRole.values()) {
            if (!text.contains(role.getRoleName())) {
                missing.add(role.getRoleName());
            }
        }

        assertThat(missing)
                .as("roles in OrgRole that docs/org-scoped-roles.md never names")
                .isEmpty();
    }

    @Test
    void theGuideDescribesWhatOrgManagerActuallyGrants() throws IOException {
        String text = guide();

        // org-manager gates no endpoint, which is easy to mistake for granting nothing. It is one of
        // OrgRole.getManagerRoles(), and that set decides who may be named the manager on a project
        // invite code and who appears in the manager listings, so removing it is a behaviour change.
        assertThat(OrgRole.getManagerRoles()).contains(OrgRole.ORG_MANAGER);

        assertThat(text)
                .as("naming getManagerRoles is not enough: the guide has to say what holding one "
                        + "of them does, or a reader still concludes org-manager is inert")
                .contains("getManagerRoles")
                .contains("project invite code")
                .contains("manager listings");
    }
}
