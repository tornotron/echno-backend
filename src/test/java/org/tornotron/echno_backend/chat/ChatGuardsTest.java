package org.tornotron.echno_backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every chat handler asks the access matrix for the chat action it performs, and nothing else: reads,
 * the stream and marking a room read need read, opening a room, posting, reacting and archiving need
 * create, editing a message needs edit and deleting one needs delete. Marking a room read only moves
 * the caller's own unread marker, so a participant who may read a room may clear it. A new handler fails here until it is given its row.
 */
class ChatGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("ChatControllerWeb#readRooms", "read"),
            Map.entry("ChatControllerWeb#readRoom", "read"),
            Map.entry("ChatControllerWeb#openDirectRoom", "create"),
            Map.entry("ChatControllerWeb#markRoomRead", "read"),
            Map.entry("ChatControllerWeb#readMessages", "read"),
            Map.entry("ChatControllerWeb#sendMessage", "create"),
            Map.entry("ChatControllerWeb#sendMessageWithAttachments", "create"),
            Map.entry("ChatControllerWeb#editMessage", "edit"),
            Map.entry("ChatControllerWeb#toggleReaction", "create"),
            Map.entry("ChatControllerWeb#deleteMessage", "delete"),
            Map.entry("ChatControllerWeb#archiveRoom", "create"),
            Map.entry("ChatControllerWeb#stream", "read"));

    @Test
    void everyHandlerNeedsTheChatActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(ChatGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('chat', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(ChatControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
