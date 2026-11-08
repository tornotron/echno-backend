package org.tornotron.echno_backend.common.openapi;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tags an operation with its own controller's tag, not also with the tag of a controller it
 * extends.
 *
 * <p>The mobile paths for the web-only modules (#998) are served by subclasses of the web
 * controllers, and springdoc collects class-level {@code @Tag}s up the class hierarchy. Without
 * this every mobile operation was listed under its web module's tag as well as its own, so a
 * client generated per tag got the mobile operations twice and the web group got paths it does
 * not call. A controller that declares no {@code @Tag} of its own is left as springdoc tagged it,
 * which is every controller that extends nothing.
 */
@Component
public class OwnControllerTagCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        List<String> tags = operation.getTags();
        if (tags == null || tags.isEmpty()) {
            return operation;
        }
        Class<?> controller = handlerMethod.getBeanType();
        Set<String> own = namesOf(controller);
        if (own.isEmpty()) {
            return operation;
        }
        Set<String> inherited = new HashSet<>();
        for (Class<?> parent = controller.getSuperclass(); parent != null && parent != Object.class;
             parent = parent.getSuperclass()) {
            inherited.addAll(namesOf(parent));
        }
        inherited.removeAll(own);
        if (!inherited.isEmpty()) {
            operation.setTags(tags.stream().filter(tag -> !inherited.contains(tag)).toList());
        }
        return operation;
    }

    private static Set<String> namesOf(Class<?> type) {
        Set<String> names = new HashSet<>();
        Arrays.stream(type.getDeclaredAnnotationsByType(Tag.class)).map(Tag::name).forEach(names::add);
        return names;
    }
}
