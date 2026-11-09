package org.tornotron.echno_backend.issue;

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
 * Every issue handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * issues action it performs, and nothing else: the reads need read and each write needs the create,
 * edit or delete it makes. Comments are an area of their own, issue-comments, pinned by
 * {@code IssueCommentGuardsTest}. A new handler fails here until it is given its row.
 */
class IssueGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("IssueController#readAnIssue", "read"),
            Map.entry("IssueController#createIssue", "create"),
            Map.entry("IssueController#partialUpdateAnIssue", "edit"),
            Map.entry("IssueController#deleteAnIssue", "delete"),
            Map.entry("IssueControllerWeb#readAllIssues", "read"),
            Map.entry("IssueControllerWeb#readAllIssuesPaginated", "read"),
            Map.entry("IssueControllerWeb#readIssueStats", "read"),
            Map.entry("IssueControllerWeb#readAllIssuesOfProject", "read"),
            Map.entry("IssueControllerWeb#readAnIssue", "read"),
            Map.entry("IssueControllerWeb#createIssue", "create"),
            Map.entry("IssueControllerWeb#partialUpdateAnIssue", "edit"),
            Map.entry("IssueControllerWeb#readAllIssuesForTask", "read"),
            Map.entry("IssueControllerWeb#deleteAnIssue", "delete"));

    @Test
    void everyHandlerNeedsTheIssuesActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(IssueGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('issues', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(IssueController.class, IssueControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
