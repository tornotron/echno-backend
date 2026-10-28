package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A permission parses from and prints as {@code <area>:<action>}, the vocabulary module
 * manifests declare, and refuses any other form.
 */
class AccessPermissionTest {

    @Test
    void parsesAreaAndAction() {
        AccessPermission certify = AccessPermission.of("progress-bills:certify");

        assertThat(certify.area()).isEqualTo("progress-bills");
        assertThat(certify.action()).isEqualTo("certify");
        assertThat(certify.key()).isEqualTo("progress-bills:certify");
        assertThat(certify).isEqualTo(new AccessPermission("progress-bills", "certify"));
    }

    @Test
    void refusesTheWrongForm() {
        assertThatThrownBy(() -> AccessPermission.of("progress-bills.certify"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessPermission.of("finance:view:all"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessPermission("Finance", "view"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
