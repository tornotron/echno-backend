package org.tornotron.echno_backend.project;

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
import org.tornotron.echno_backend.category.CategoryController;
import org.tornotron.echno_backend.category.CategoryControllerWeb;
import org.tornotron.echno_backend.project.spatial.SpatialNodeController;

/**
 * Every handler of the projects area asks the access matrix for the action it performs, and nothing
 * else: reads need read, a change needs edit, a removal needs delete, and creating a record, adding
 * someone to a team or restructuring a site needs create (the action the permission mapping gives
 * them). A new handler on these controllers fails here until it is given its row.
 *
 * <p>{@link CategoryController}, the mobile twin, carries its web twin's guards
 * ({@code MobileWebGuardParityTest}), so all of its handlers are listed too.
 */
class ProjectGuardsTest {

    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("ProjectControllerWeb#createProject", "create"),
            Map.entry("ProjectControllerWeb#readAllProjects", "read"),
            Map.entry("ProjectControllerWeb#readAllProjectsPaginated", "read"),
            Map.entry("ProjectControllerWeb#readAllProjectSummaries", "read"),
            Map.entry("ProjectControllerWeb#readAProject", "read"),
            Map.entry("ProjectControllerWeb#readStatusHistory", "read"),
            Map.entry("ProjectControllerWeb#partialUpdateAProject", "edit"),
            Map.entry("ProjectController#batchUpdateProjects", "edit"),
            Map.entry("ProjectControllerWeb#deleteProject", "delete"),
            Map.entry("ProjectControllerWeb#addEmployeeToProject", "create"),
            Map.entry("ProjectControllerWeb#removeEmployeeFromProject", "delete"),
            Map.entry("ProjectControllerWeb#getEmployeesByProjectId", "read"),
            Map.entry("ProjectControllerWeb#getProjectsByEmployeeId", "read"),
            Map.entry("SpatialNodeController#getTree", "read"),
            Map.entry("SpatialNodeController#getNode", "read"),
            Map.entry("SpatialNodeController#create", "create"),
            Map.entry("SpatialNodeController#update", "edit"),
            Map.entry("SpatialNodeController#move", "create"),
            Map.entry("SpatialNodeController#archive", "create"),
            Map.entry("SpatialNodeController#restore", "create"),
            Map.entry("SpatialNodeController#importRows", "create"),
            Map.entry("CategoryControllerWeb#createCategory", "create"),
            Map.entry("CategoryControllerWeb#readAllCategories", "read"),
            Map.entry("CategoryControllerWeb#readACategory", "read"),
            Map.entry("CategoryControllerWeb#deleteACategory", "delete"),
            Map.entry("CategoryControllerWeb#readSubcategories", "read"),
            Map.entry("CategoryController#readSubcategories", "read"),
            Map.entry("CategoryController#createCategory", "create"),
            Map.entry("CategoryController#readAllCategories", "read"),
            Map.entry("CategoryController#readACategory", "read"),
            Map.entry("CategoryController#deleteACategory", "delete"));

    @Test
    void everyHandlerNeedsTheProjectsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(ProjectGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('projects', '" + action + "')"));
    }

    /** The handlers on the access matrix: every one of the four controllers. */
    static List<Method> handlers() {
        return Stream.of(
                        handlersOf(ProjectControllerWeb.class).stream(),
                        handlersOf(SpatialNodeController.class).stream(),
                        handlersOf(CategoryControllerWeb.class).stream(),
                        handlersOf(CategoryController.class).stream(),
                        // The mobile batch update has no /web twin; the rest of the mobile controller
                        // carries the web twin's guards.
                        handlersOf(ProjectController.class).stream()
                                .filter(handler -> handler.getName().equals("batchUpdateProjects")))
                .flatMap(handlers -> handlers)
                .toList();
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    private static List<Method> handlersOf(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
