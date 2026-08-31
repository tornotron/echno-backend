package org.tornotron.echno_backend.common.pagination;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Writes each paginated operation's {@code pageSize} default into the document from the page-query
 * type that operation declares.
 *
 * <p>{@link PageQuery} is one parameter object shared by fifty-odd endpoints, so a
 * {@code defaultValue} written on its {@code pageSize} field is one number for all of them.
 * Thirteen of them do not serve that number: the twelve listings on {@link PageQuery20} serve
 * twenty and the chat messages listing on {@link PageQuery30} serves thirty. The document said ten
 * for all fifty-odd regardless, which is how thirteen listings came to publish a page size no
 * caller would ever receive, and how a client reading the contract could plan for ten rows, ask
 * for none, and be handed twenty. Issue #662.
 *
 * <p>The fix has to be one source of truth rather than two that happen to agree, because two is
 * the arrangement that produced this. So nothing here is declared: the customizer constructs the
 * declared parameter type exactly as Spring's model-attribute binding will, and reads
 * {@link PageQuery#getPageSize()} off it. That is by construction the value a caller who sends no
 * {@code pageSize} gets, since binding starts from the same constructor and then writes nothing
 * over it. An endpoint's default can only move by moving the constructor that sets it, and the
 * document moves with it.
 *
 * <p>Every paginated operation is written, including the ones on the shared default, so the
 * document has no default that this class did not put there. A springdoc that started sharing one
 * {@code Schema} instance between operations would then show one number across all of them rather
 * than quietly corrupting a few, and {@code OpenApiPageSizeDefaultTest} would say so.
 *
 * <h2>Why an operation customizer</h2>
 *
 * <p>The value belongs to the handler, not to the type being resolved, and an
 * {@link OperationCustomizer} is the only springdoc hook that is handed both: the assembled
 * operation and the {@link HandlerMethod} behind it. A model converter sees {@code int} with no
 * idea which endpoint asked, and a schema annotation on the field cannot vary by caller at all.
 */
@Component
@Slf4j
public class PageSizeSchemaCustomizer implements OperationCustomizer {

    /** The query parameter this class exists to describe. */
    private static final String PAGE_SIZE = "pageSize";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Integer servedDefault = servedDefault(handlerMethod);
        if (servedDefault == null) {
            return operation;
        }
        List<Parameter> parameters = operation.getParameters();
        if (parameters == null) {
            return operation;
        }
        for (Parameter parameter : parameters) {
            if (!PAGE_SIZE.equals(parameter.getName())) {
                continue;
            }
            Schema<?> schema = parameter.getSchema();
            if (schema == null) {
                // Nothing to describe the default on. Worth knowing about rather than silently
                // skipping, since it would mean the parameter object stopped being expanded.
                log.warn("pageSize on {} has no schema to carry its default of {}",
                        handlerMethod.getShortLogMessage(), servedDefault);
                continue;
            }
            schema.setDefault(servedDefault);
        }
        return operation;
    }

    /**
     * The rows this handler serves a caller who sends no {@code pageSize}.
     *
     * @param handlerMethod The handler behind the operation being described.
     * @return The served default, or {@code null} if the handler takes no page query at all.
     */
    private Integer servedDefault(HandlerMethod handlerMethod) {
        for (MethodParameter methodParameter : handlerMethod.getMethodParameters()) {
            Class<?> type = methodParameter.getParameterType();
            if (!PageQuery.class.isAssignableFrom(type)) {
                continue;
            }
            try {
                return ((PageQuery) type.getDeclaredConstructor().newInstance()).getPageSize();
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                     | InvocationTargetException e) {
                // Binding constructs the same type the same way, so a type that cannot be
                // constructed here could not have served a request either.
                throw new IllegalStateException(
                        "A page query a handler declares must be constructible: " + type.getName(), e);
            }
        }
        return null;
    }
}
