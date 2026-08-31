package org.tornotron.echno_backend.common.pagination;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the customizer writes onto an operation, per shape of handler.
 *
 * <p>The rule that matters is in {@code OpenApiPageSizeDefaultTest}, which holds the committed
 * document to the endpoints for real. These are the cases that rule cannot reach: a handler with
 * no page query at all, an operation with no parameters, and a {@code pageSize} the parameter
 * object failed to give a schema. Each of those is a way the customizer could throw during
 * document assembly, which fails the build in a place that says nothing about pagination.
 */
class PageSizeSchemaCustomizerTest {

    private final PageSizeSchemaCustomizer customizer = new PageSizeSchemaCustomizer();

    @Test
    void publishesTheSharedDefaultForAHandlerOnTheSharedPageQuery() {
        Operation operation = operationWithPageSize();

        customizer.customize(operation, handlerFor("shared"));

        assertThat(pageSizeDefault(operation)).isEqualTo(PageQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void publishesTwentyForAHandlerThatShippedWithTwenty() {
        Operation operation = operationWithPageSize();

        customizer.customize(operation, handlerFor("twenty"));

        assertThat(pageSizeDefault(operation)).isEqualTo(PageQuery20.PAGE_SIZE);
    }

    @Test
    void publishesThirtyForAHandlerThatShippedWithThirty() {
        Operation operation = operationWithPageSize();

        customizer.customize(operation, handlerFor("thirty"));

        assertThat(pageSizeDefault(operation)).isEqualTo(PageQuery30.PAGE_SIZE);
    }

    /**
     * The default is read off the type the handler declares, so it is the size a caller who sends
     * no {@code pageSize} is actually left holding rather than a number written twice.
     */
    @Test
    void publishesTheSizeBindingWouldLeaveTheHandlerWith() {
        Operation operation = operationWithPageSize();

        customizer.customize(operation, handlerFor("twenty"));

        assertThat(pageSizeDefault(operation)).isEqualTo(new PageQuery20().getPageSize());
    }

    @Test
    void leavesOtherParametersAlone() {
        Operation operation = operationWithPageSize();
        Parameter search = new Parameter().name("search").schema(new StringSchema());
        operation.getParameters().add(search);

        customizer.customize(operation, handlerFor("twenty"));

        assertThat(search.getSchema().getDefault()).isNull();
    }

    @Test
    void writesNothingForAHandlerThatTakesNoPageQuery() {
        Operation operation = operationWithPageSize();

        customizer.customize(operation, handlerFor("unpaginated"));

        assertThat(pageSizeDefault(operation)).isNull();
    }

    @Test
    void toleratesAnOperationWithNoParameters() {
        Operation operation = new Operation();

        assertThatCode(() -> customizer.customize(operation, handlerFor("twenty")))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesAPageSizeParameterWithNoSchema() {
        Operation operation = new Operation();
        operation.setParameters(new ArrayList<>(List.of(new Parameter().name("pageSize"))));

        assertThatCode(() -> customizer.customize(operation, handlerFor("twenty")))
                .doesNotThrowAnyException();
    }

    private static Operation operationWithPageSize() {
        Operation operation = new Operation();
        operation.setParameters(new ArrayList<>(List.of(
                new Parameter().name("pageNo").schema(new IntegerSchema()),
                new Parameter().name("pageSize").schema(new IntegerSchema()))));
        return operation;
    }

    private static Object pageSizeDefault(Operation operation) {
        return operation.getParameters().stream()
                .filter(parameter -> "pageSize".equals(parameter.getName()))
                .findFirst()
                .orElseThrow()
                .getSchema()
                .getDefault();
    }

    private static HandlerMethod handlerFor(String methodName) {
        StubController controller = new StubController();
        for (Method method : StubController.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return new HandlerMethod(controller, method);
            }
        }
        throw new IllegalArgumentException("No stub handler named " + methodName);
    }

    /** The handler shapes the customizer has to answer for, standing in for a real controller. */
    @SuppressWarnings("unused")
    static class StubController {

        public void shared(PageQuery pageQuery) {
        }

        public void twenty(PageQuery20 pageQuery) {
        }

        public void thirty(Long roomId, PageQuery30 pageQuery) {
        }

        public void unpaginated(Long id) {
        }
    }
}
