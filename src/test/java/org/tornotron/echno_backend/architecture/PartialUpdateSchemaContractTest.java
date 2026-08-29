package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto;
import org.tornotron.echno_backend.issue.dto.IssueUpdateFieldsDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyUpdateFieldsDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestUpdateFieldsDto;
import org.tornotron.echno_backend.organization.dto.OrganizationUpdateFieldsDto;
import org.tornotron.echno_backend.project.dto.ProjectUpdateFieldsDto;
import org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto;
import org.tornotron.echno_backend.user.dto.UserUpdateFieldsDto;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds each partial-update schema to the keys its service actually accepts.
 *
 * <p>A partial update takes its body as a {@code Map<String, Object>}, because it has to tell a
 * field that was left out from a field that was sent as null, and a bean cannot: both arrive as a
 * null property, and several of these fields are cleared by sending an explicit null. The map is
 * therefore the right runtime shape and is not what this test is trying to change.
 *
 * <p>What the map costs is the published contract. A map is {@code additionalProperties} in
 * OpenAPI, so the document says nothing about which keys exist or what they hold, no key can ever
 * fail against it, and a caller sending a key the service does not know gets a 200 and no change.
 * The {@code *UpdateFieldsDto} classes supply that missing half: they are named as the request
 * schema on the endpoint and never deserialized into.
 *
 * <p>Which leaves the obvious failure mode. A schema that describes nothing at runtime is a schema
 * nothing keeps true, and a documentation class that drifts from the code is worse than no
 * documentation, because it reads as authoritative. So this test reads the {@code case "..."}
 * labels out of the service method's own source and fails when they and the schema's fields have
 * moved apart in either direction. Adding a key to a service switch without adding it to the schema
 * fails here, and so does the reverse.
 *
 * <p>Source rather than bytecode because a {@code switch} over strings compiles to a hash cascade
 * that no longer carries the labels in a form worth reconstructing. Plain JUnit rather than a
 * Spring context, because it reads two files and needs nothing else.
 */
class PartialUpdateSchemaContractTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * A {@code case} label in a string switch. Matches both the arrow and colon forms, since the
     * services use one each.
     */
    private static final Pattern CASE_LABEL = Pattern.compile("case\\s+\"([^\"]+)\"");

    /**
     * One partial-update surface: the schema class published for it, and the service method whose
     * switch decides what it really accepts.
     *
     * @param schema The documentation-only class named as the endpoint's request schema.
     * @param serviceClass Fully qualified name of the service holding the update method.
     * @param methodSignature The opening text of the method declaration, used to find it in the
     *                        source. Must be unique within the file.
     */
    private record UpdateSurface(Class<?> schema, String serviceClass, String methodSignature) {
        @Override
        public String toString() {
            return schema.getSimpleName();
        }
    }

    static List<UpdateSurface> surfaces() {
        return List.of(
                new UpdateSurface(
                        TaskUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.task.TaskService",
                        "private void partialUpdateATask(Map<String, Object> updates, Task task)"),
                new UpdateSurface(
                        IssueUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.issue.IssueService",
                        "private void partialUpdateAnIssue(Map<String, Object> updates, Issue issue)"),
                new UpdateSurface(
                        ProjectUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.project.ProjectService",
                        "private void partialUpdateAProject(Map<String, Object> updates, Project project)"),
                new UpdateSurface(
                        OrganizationUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.organization.OrganizationService",
                        "private void partialUpdateAnOrganization(Map<String, Object> updates, Organization organization)"),
                new UpdateSurface(
                        UserUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.user.UserService",
                        "private void applyUpdates(Map<String, Object> updates, User user)"),
                new UpdateSurface(
                        EmployeeUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.employee.EmployeeService",
                        "private void partialUpdateAnEmployee(Map<String, Object> updates, Employee employee)"),
                new UpdateSurface(
                        LeavePolicyUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.leave.LeavePolicyService",
                        "public LeavePolicyDto updatePolicy(Long policyId, Map<String, Object> updates)"),
                new UpdateSurface(
                        LeaveRequestUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.leave.LeaveRequestService",
                        "public LeaveRequestDto updateRequest(Long requestId, Map<String, Object> updates)"));
    }

    @ParameterizedTest(name = "{0} describes exactly the keys its service accepts")
    @MethodSource("surfaces")
    @DisplayName("A published partial-update schema names every accepted key and no others")
    void schemaMatchesTheKeysTheServiceAccepts(UpdateSurface surface) throws IOException {
        Set<String> accepted = acceptedKeys(surface);
        Set<String> published = publishedFields(surface.schema());

        assertThat(published)
                .as("%s is the request schema published for this endpoint, so it has to name "
                        + "exactly the keys %s applies. A key in the service and not the schema is "
                        + "undocumented; a field in the schema and not the service is a documented "
                        + "field the endpoint silently ignores.",
                        surface.schema().getSimpleName(), surface.serviceClass())
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    /** The keys the service's update switch names, read out of the method's own source. */
    private static Set<String> acceptedKeys(UpdateSurface surface) throws IOException {
        String body = methodBody(surface);
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = CASE_LABEL.matcher(body);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertThat(keys)
                .as("no case labels found in %s; the method signature this test looks for has "
                        + "probably been reworded", surface.methodSignature())
                .isNotEmpty();
        return keys;
    }

    /** The properties the schema class declares, which is what springdoc publishes. */
    private static Set<String> publishedFields(Class<?> schema) {
        return java.util.Arrays.stream(schema.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The text of the named method, from its declaration to its closing brace.
     *
     * <p>Brace counting is enough here and does not need a parser: these methods hold a switch over
     * string literals and no brace ever appears inside one of them.
     */
    private static String methodBody(UpdateSurface surface) throws IOException {
        Path source = SOURCE_ROOT.resolve(surface.serviceClass().replace('.', '/') + ".java");
        assertThat(source)
                .as("service source for %s, read relative to the project directory", surface.serviceClass())
                .exists();
        String text = Files.readString(source);

        int start = text.indexOf(surface.methodSignature());
        assertThat(start)
                .as("method %s was not found in %s; if it has been renamed or its parameters "
                        + "reformatted, update the signature this test looks for",
                        surface.methodSignature(), source)
                .isNotEqualTo(-1);
        assertThat(text.indexOf(surface.methodSignature(), start + 1))
                .as("method signature %s appears more than once in %s", surface.methodSignature(), source)
                .isEqualTo(-1);

        int cursor = text.indexOf('{', start);
        int depth = 0;
        for (int i = cursor; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(cursor, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces after " + surface.methodSignature() + " in " + source);
    }
}
