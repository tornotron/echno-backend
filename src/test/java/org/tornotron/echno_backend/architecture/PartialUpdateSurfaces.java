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
     */
    record UpdateSurface(Class<?> schema, String serviceClass, String methodSignature) {
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
