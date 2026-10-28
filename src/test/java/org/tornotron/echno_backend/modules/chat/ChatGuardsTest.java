package org.tornotron.echno_backend.modules.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every chat handler asks the access matrix, never a role name or bare membership, and every
 * read needs only {@code chat:view}.
 */
class ChatGuardsTest {

    private static final List<Method> HANDLERS = Arrays.stream(ChatControllerWeb.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> Arrays.stream(method.getAnnotations())
                    .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
            .toList();

    @Test
    void everyHandlerCarriesOneOfTheChatGuards() {
        assertThat(HANDLERS).isNotEmpty().allSatisfy(handler -> {
            PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
            assertThat(guard).as("%s has a guard", handler.getName()).isNotNull();
            assertThat(guard.value()).as("%s asks the access matrix", handler.getName())
                    .isIn(ChatModule.VIEW_GUARD, ChatModule.PARTICIPATE_GUARD);
        });
    }

    @Test
    void everyReadNeedsOnlyView() {
        assertThat(HANDLERS).filteredOn(handler -> handler.isAnnotationPresent(GetMapping.class))
                .isNotEmpty()
                .allSatisfy(handler -> assertThat(handler.getAnnotation(PreAuthorize.class).value())
                        .as("%s reads, so it needs view", handler.getName())
                        .isEqualTo(ChatModule.VIEW_GUARD));
    }
}
