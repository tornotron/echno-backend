package org.tornotron.echno_backend.common.openapi;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mobile controller that extends its web twin is listed under its own tag only (#998).
 */
class OwnControllerTagCustomizerTest {

    @Tag(name = "Things")
    static class ThingControllerWeb {
        @GetMapping("/things/web")
        public void list() {
        }
    }

    @Tag(name = "Things (Mobile)")
    static class ThingController extends ThingControllerWeb {
    }

    static class UntaggedSubclass extends ThingControllerWeb {
    }

    private final OwnControllerTagCustomizer customizer = new OwnControllerTagCustomizer();

    private List<String> tagsOf(Object controller, String... springdocTags) throws Exception {
        HandlerMethod handler = new HandlerMethod(controller, controller.getClass().getMethod("list"));
        Operation operation = new Operation().tags(new ArrayList<>(List.of(springdocTags)));
        return customizer.customize(operation, handler).getTags();
    }

    @Test
    void theSubclassKeepsOnlyItsOwnTag() throws Exception {
        assertThat(tagsOf(new ThingController(), "Things", "Things (Mobile)"))
                .containsExactly("Things (Mobile)");
    }

    @Test
    void aControllerThatExtendsNothingIsLeftAlone() throws Exception {
        assertThat(tagsOf(new ThingControllerWeb(), "Things")).containsExactly("Things");
    }

    @Test
    void aSubclassWithNoTagOfItsOwnIsLeftAlone() throws Exception {
        assertThat(tagsOf(new UntaggedSubclass(), "Things")).containsExactly("Things");
    }
}
