package org.tornotron.echno_backend.common.openapi;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operation id depends only on its handler (#878). springdoc's default numbers repeats of a
 * method name in the order it meets them, so adding a controller renumbered unrelated operations.
 */
class StableOperationIdCustomizerTest {

    static class FirstController {
        @PostMapping("/first")
        public void create() {
        }

        @GetMapping("/first/{id}")
        public void read(Long id) {
        }

        @GetMapping(value = "/first/{id}", params = "name")
        public void read(Long id, String name) {
        }

        @GetMapping("/first/named")
        @Operation(operationId = "chosenByHand")
        public void named() {
        }

        public void read(String notAHandler, int count) {
        }
    }

    static class SecondController {
        @PostMapping("/second")
        public void create() {
        }
    }

    private final StableOperationIdCustomizer customizer = new StableOperationIdCustomizer();

    private String idOf(Object controller, String method, Class<?>... parameters) throws Exception {
        HandlerMethod handler = new HandlerMethod(controller, controller.getClass().getMethod(method, parameters));
        // springdoc's own numbered id is what arrives here, and it must not survive
        return customizer.customize(new io.swagger.v3.oas.models.Operation().operationId(method + "_7"), handler)
                .getOperationId();
    }

    @Test
    void theIdIsTheControllerAndTheMethod_whateverSpringdocNumberedIt() throws Exception {
        assertThat(idOf(new FirstController(), "create")).isEqualTo("FirstController_create");
        assertThat(idOf(new SecondController(), "create")).isEqualTo("SecondController_create");
    }

    @Test
    void overloadsAreToldApartByTheirParameterTypes() throws Exception {
        assertThat(idOf(new FirstController(), "read", Long.class)).isEqualTo("FirstController_read_Long");
        assertThat(idOf(new FirstController(), "read", Long.class, String.class))
                .isEqualTo("FirstController_read_Long_String");
    }

    @Test
    void anIdWrittenOnTheOperationIsKept() throws Exception {
        assertThat(idOf(new FirstController(), "named")).isEqualTo("chosenByHand");
    }
}
