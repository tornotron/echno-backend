package org.tornotron.echno_backend.IssueComment;

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
 * Every issue comment handler, on the tenant API and its {@code /web} twin, asks the access matrix for
 * the issue-comments action it performs, and nothing else: the reads need read and each write needs the
 * create, edit or delete it makes. A new handler fails here until it is given its row.
 */
class IssueCommentGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("IssueCommentController#createIssueComment", "create"),
            Map.entry("IssueCommentController#readAllIssueComments", "read"),
            Map.entry("IssueCommentController#deleteAnIssueComment", "delete"),
            Map.entry("IssueCommentControllerWeb#createIssueComment", "create"),
            Map.entry("IssueCommentControllerWeb#readAllIssueComments", "read"),
            Map.entry("IssueCommentControllerWeb#getAnIssueComment", "read"),
            Map.entry("IssueCommentControllerWeb#getAllIssueCommentsByIssueId", "read"),
            Map.entry("IssueCommentControllerWeb#updateIssueComment", "edit"),
            Map.entry("IssueCommentControllerWeb#deleteAnIssueComment", "delete"));

    @Test
    void everyHandlerNeedsTheIssueCommentsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(IssueCommentGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('issue-comments', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(IssueCommentController.class, IssueCommentControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
