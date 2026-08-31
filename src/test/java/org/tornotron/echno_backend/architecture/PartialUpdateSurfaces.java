package org.tornotron.echno_backend.architecture;

import org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto;
import org.tornotron.echno_backend.issue.dto.IssueUpdateFieldsDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyUpdateFieldsDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestUpdateFieldsDto;
import org.tornotron.echno_backend.organization.dto.OrganizationUpdateFieldsDto;
import org.tornotron.echno_backend.project.dto.ProjectUpdateFieldsDto;
import org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto;
import org.tornotron.echno_backend.user.dto.UserUpdateFieldsDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every partial-update endpoint in the application, and the one place that list is written down.
 *
 * <p>These endpoints take their body as a {@code Map<String, Object>} and apply it with a
 * {@code switch} over the keys. Two separate things have to be true of each of them and neither is
 * visible from the map: the schema published for it names exactly the keys it applies
 * ({@link PartialUpdateSchemaContractTest}), and a key it does not name is reported rather than
 * dropped in silence ({@link PartialUpdateDefaultBranchTest}). Both read the service's own source,
 * so both need the same list and the same way of finding a method in a file.
 *
 * <p>A new partial-update endpoint belongs here, and adding it makes both tests apply to it.
 */
final class PartialUpdateSurfaces {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    private PartialUpdateSurfaces() {
    }

    /**
     * One partial-update surface: the schema class published for it, and the service method whose
     * switch decides what it really accepts.
     *
     * @param schema The documentation-only class named as the endpoint's request schema.
     * @param serviceClass Fully qualified name of the service holding the update method.
     * @param methodSignature The opening text of the method declaration, used to find it in the
     *                        source. Must be unique within the file.
     * @param refusesNull The keys that refuse an explicit null with a 400 instead of clearing the
     *                    field. Every other key on the surface clears. See {@link #refusesNull()}
     *                    for why this is written down here rather than read off the source.
     */
    record UpdateSurface(Class<?> schema, String serviceClass, String methodSignature,
                         Set<String> refusesNull) {
        @Override
        public String toString() {
            return schema.getSimpleName();
        }
    }

    /**
     * The surfaces, and for each the keys that refuse a null.
     *
     * <p>A partial update has to tell "field absent" from "field explicitly null", which is why
     * these endpoints take a map rather than a bean. Absence always means untouched. What an
     * explicit null means was, until #645, three different things depending on the key: most
     * cleared the field, two refused with a 400, and eleven reached an unguarded
     * {@code Enum.valueOf(null)} or {@code ((Number) null).longValue()} and answered 500. The
     * class-level schema on {@code TaskUpdateFieldsDto} meanwhile stated flatly that a null clears,
     * which was true of seven of its nine fields.
     *
     * <p>The rule the eleven were settled by is not a matter of taste: <b>a null is refused where
     * the column behind the field is {@code NOT NULL}, and clears where it is nullable</b>. The
     * database already enforces that, so the alternative was a 500 now or a constraint violation
     * at flush. Two keys refuse a null although their column is nullable, and both say so on their
     * own schema: {@code TaskUpdateFieldsDto.categoryId} is a stated product rule, and
     * {@code IssueUpdateFieldsDto.type} and {@code status} are required by the enum parser they
     * share with creation.
     *
     * <p>This list is written down rather than derived from the source because the two facts that
     * have to agree live in different places: what the service does with a null, and what the
     * published schema says about it. {@code PartialUpdateNullBehaviourTest} holds the first to
     * this list by calling each surface with each key set to null, and
     * {@code PartialUpdateNullContractTest} holds the second to it by reading the annotations. A
     * key added to a switch without a decision fails both.
     *
     * @return Every partial-update surface in the application.
     */
    static List<UpdateSurface> surfaces() {
        return List.of(
                new UpdateSurface(
                        TaskUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.task.TaskService",
                        "private void partialUpdateATask(Map<String, Object> updates, Task task)",
                        Set.of("status", "categoryId")),
                new UpdateSurface(
                        IssueUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.issue.IssueService",
                        "private void partialUpdateAnIssue(Map<String, Object> updates, Issue issue)",
                        Set.of("type", "status")),
                new UpdateSurface(
                        ProjectUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.project.ProjectService",
                        "private void partialUpdateAProject(Map<String, Object> updates, Project project)",
                        Set.of()),
                new UpdateSurface(
                        OrganizationUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.organization.OrganizationService",
                        "private void partialUpdateAnOrganization(Map<String, Object> updates, Organization organization)",
                        Set.of()),
                new UpdateSurface(
                        UserUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.user.UserService",
                        "private void applyUpdates(Map<String, Object> updates, User user)",
                        Set.of()),
                new UpdateSurface(
                        EmployeeUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.employee.EmployeeService",
                        "private void partialUpdateAnEmployee(Map<String, Object> updates, Employee employee)",
                        Set.of()),
                new UpdateSurface(
                        LeavePolicyUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.leave.LeavePolicyService",
                        "public LeavePolicyDto updatePolicy(Long policyId, Map<String, Object> updates)",
                        Set.of("annualQuota")),
                new UpdateSurface(
                        LeaveRequestUpdateFieldsDto.class,
                        "org.tornotron.echno_backend.leave.LeaveRequestService",
                        "public LeaveRequestDto updateRequest(Long requestId, Map<String, Object> updates)",
                        Set.of("startDate", "endDate")));
    }

    /**
     * The text of the surface's update method, from its declaration to its closing brace.
     *
     * <p>Source rather than bytecode because a {@code switch} over strings compiles to a hash
     * cascade that no longer carries the labels in a form worth reconstructing.
     *
     * <p>Brace counting is enough here and does not need a parser: these methods hold a switch over
     * string literals and no brace ever appears inside one of them.
     *
     * @param surface The surface whose method to read.
     * @return The method's source text.
     * @throws IOException if the service source cannot be read.
     */
    static String methodBody(UpdateSurface surface) throws IOException {
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
