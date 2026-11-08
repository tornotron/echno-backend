package org.tornotron.echno_backend.common.openapi;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Names each operation after its controller and method, so an operation id depends only on the
 * handler it describes.
 *
 * <p>springdoc's default is the method name, with a numeric suffix for each repeat in the order it
 * meets them. Many controllers have a {@code create}, so adding one more renumbers the ones after
 * it, and one new module once changed 73 unrelated ids in {@code docs/openapi.json} (#878).
 *
 * <p>A controller's own overloads share a name, so one that has them adds its parameter types. An
 * id written out on {@code @Operation} is left as written.
 */
@Component
public class StableOperationIdCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        String declared = explicitId(handlerMethod);
        return operation.operationId(declared != null ? declared : idOf(handlerMethod));
    }

    private static String explicitId(HandlerMethod handlerMethod) {
        io.swagger.v3.oas.annotations.Operation declared =
                handlerMethod.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        return declared == null || declared.operationId().isBlank() ? null : declared.operationId();
    }

    private static String idOf(HandlerMethod handlerMethod) {
        Class<?> controller = handlerMethod.getBeanType();
        Method method = handlerMethod.getMethod();
        String id = controller.getSimpleName() + "_" + method.getName();
        return isOverloaded(controller, method)
                ? id + "_" + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining("_"))
                : id;
    }

    private static boolean isOverloaded(Class<?> controller, Method method) {
        return Arrays.stream(controller.getMethods())
                .filter(candidate -> candidate.getName().equals(method.getName()))
                .filter(candidate -> AnnotatedElementUtils.hasAnnotation(candidate, RequestMapping.class))
                .count() > 1;
    }
}
