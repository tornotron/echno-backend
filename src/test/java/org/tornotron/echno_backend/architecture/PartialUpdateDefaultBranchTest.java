package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tornotron.echno_backend.architecture.PartialUpdateSurfaces.UpdateSurface;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every partial-update switch has to say what it does with a key it does not know.
 *
 * <p>A {@code switch} over the keys of an update map with no {@code default} is not neutral. The
 * key falls off the end, the endpoint answers 200, and the caller is told a change was made that
 * was not. Changing an issue's type through the product did nothing for months for exactly that
 * reason, and the same shape sat under seven more endpoints. It is invisible in review because
 * there is nothing on the screen to see: the absence is the bug.
 *
 * <p>So this reads each update method's own source and fails when the switch has no {@code default}
 * branch, or when that branch does not go through
 * {@code org.tornotron.echno_backend.common.payload.PartialUpdateKeys}. Naming the helper matters
 * as much as having the branch: a {@code default} that is an empty block is the same silent drop
 * written down, and one of these was exactly that before.
 *
 * <p>What the branch then does with the key, warn or refuse, is a decision per endpoint and not
 * something a test should fix. It is warn everywhere today, because the deployed web client puts
 * keys in these payloads that no endpoint has a field for, so refusing would turn ordinary edits
 * into failures. The reasoning is on {@code PartialUpdateKeys} and the per-service constants.
 */
class PartialUpdateDefaultBranchTest {

    private static final String HELPER = "PartialUpdateKeys.reportUnknown";

    static List<UpdateSurface> surfaces() {
        return PartialUpdateSurfaces.surfaces();
    }

    @ParameterizedTest(name = "{0}''s update reports a key it has no field for")
    @MethodSource("surfaces")
    @DisplayName("A partial-update switch never drops an unrecognised key in silence")
    void updateSwitchHasADefaultBranchThatReportsTheKey(UpdateSurface surface) throws IOException {
        String body = PartialUpdateSurfaces.methodBody(surface);

        assertThat(body)
                .as("the update switch in %s has no default branch, so a key it does not name is "
                        + "dropped and the caller is told the update succeeded",
                        surface.serviceClass())
                .containsPattern("default\\s*(->|:)");

        assertThat(body)
                .as("the default branch in %s has to report the key through %s; a branch that does "
                        + "nothing is the same silent drop, only written down",
                        surface.serviceClass(), HELPER)
                .contains(HELPER);
    }
}
