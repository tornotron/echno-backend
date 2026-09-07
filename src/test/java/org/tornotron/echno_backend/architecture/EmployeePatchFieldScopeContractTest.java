package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.architecture.PartialUpdateSurfaces.UpdateSurface;
import org.tornotron.echno_backend.employee.EmployeePatchFieldScope;
import org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the employee partial update to a decision about every field it accepts.
 *
 * <p>{@code PATCH /employee/{id}} admits the subject of the record as well as {@code system-admin}
 * and {@code hr-admin}, so every key it accepts is a key some employee can set on themselves
 * unless something says otherwise. {@link EmployeePatchFieldScope} is that something, and this
 * test is what stops it going stale.
 *
 * <p>The failure it exists for is not the one #735 reported. That one is fixed by the class. This
 * one is the next field: somebody adds a {@code case} to the service switch, adds the matching
 * property to {@link EmployeeUpdateFieldsDto} because
 * {@link PartialUpdateSchemaContractTest} makes them, and never learns that a third decision was
 * owed. An unclassified field would fall through to self-editable, which is the same silent
 * widening the endpoint already shipped once. So the keys are read out of the service method's own
 * source, the same way the schema contract reads them, and a key in neither set fails here.
 *
 * <p>The twin check is the other half. The mobile and web PATCHes are identical by design: #716
 * gave the mobile one the web one's guard so the two agree, and #735 is on both because of it. A
 * fix applied to one and not the other would leave the defect live on the twin, so the two guard
 * expressions are compared as text.
 */
class EmployeePatchFieldScopeContractTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    private static final Pattern CASE_LABEL = Pattern.compile("case\\s+\"([^\"]+)\"");

    /**
     * The {@code @PreAuthorize} on the PATCH of a controller: the annotation immediately preceding
     * the {@code partialUpdateAnEmployee} handler.
     */
    private static final Pattern PATCH_GUARD = Pattern.compile(
            "@PatchMapping\\(\"\\{id}\"\\)\\s*@PreAuthorize\\(\"([^\"]+)\"\\)");

    @Test
    @DisplayName("Every key the employee update accepts is either self-editable or personnel-only")
    void everyAcceptedKeyIsClassified() throws IOException {
        Set<String> accepted = acceptedKeys();
        Set<String> classified = new LinkedHashSet<>(EmployeePatchFieldScope.SELF_EDITABLE);
        classified.addAll(EmployeePatchFieldScope.PERSONNEL_ONLY);

        assertThat(classified)
                .as("EmployeePatchFieldScope decides who may set each field the employee PATCH "
                        + "accepts, and the endpoint admits the subject of the record. A key the "
                        + "service applies and this class does not name is a field every employee "
                        + "can set on themselves, which is what #735 was. Put it in SELF_EDITABLE "
                        + "if it is genuinely the person's own, and in PERSONNEL_ONLY otherwise.")
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    @Test
    @DisplayName("No field is in both sets")
    void theTwoSetsAreDisjoint() {
        assertThat(EmployeePatchFieldScope.SELF_EDITABLE)
                .as("a field cannot be both the person's own and personnel's; PERSONNEL_ONLY is "
                        + "what the check reads, so an overlap would read as a decision that was "
                        + "never taken")
                .doesNotContainAnyElementsOf(EmployeePatchFieldScope.PERSONNEL_ONLY);
    }

    @Test
    @DisplayName("The four fields the self clause exists for stay self-editable")
    void selfServiceIsNotEmptied() {
        // Written down so that tightening this further is a deliberate act. The self clause was
        // added so a person could maintain their own record from the phone; a scope that left
        // nothing self-editable would answer #735 by removing the feature instead of scoping it.
        assertThat(EmployeePatchFieldScope.SELF_EDITABLE)
                .containsExactlyInAnyOrder("employeeName", "phoneNumber", "emailAddress", "dateOfBirth");
    }

    @Test
    @DisplayName("The six fields #735 named are personnel-only")
    void theReportedFieldsArePersonnelOnly() {
        assertThat(EmployeePatchFieldScope.PERSONNEL_ONLY)
                .contains("salary", "status", "employeeId", "managerId", "designation", "department");
    }

    @Test
    @DisplayName("managerId is personnel-only, so an approver cannot be self-nominated")
    void managerIdIsPersonnelOnly() {
        // Two approval flows route on this pointer: AttendanceGeofenceService.resolveApprover
        // sends an away-from-site attendance exception to the employee's manager, and
        // LeaveApprovalService.resolveApprovalChain walks it for leave. Both refuse an approver
        // who is the subject, and neither can refuse an approver the subject appointed. Its own
        // endpoints, assignManager and removeManager, are already personnel-only.
        assertThat(EmployeePatchFieldScope.PERSONNEL_ONLY).contains("managerId");
    }

    @Test
    @DisplayName("The mobile and web PATCH guards are still the same expression")
    void bothTwinsCarryTheSameGuard() throws IOException {
        String mobile = patchGuard("org/tornotron/echno_backend/employee/EmployeeController.java");
        String web = patchGuard("org/tornotron/echno_backend/employee/EmployeeControllerWeb.java");

        assertThat(mobile)
                .as("the two employee PATCHes are identical by design (#716). The field scope runs "
                        + "below both, in the service, so it covers them whatever they say; but a "
                        + "guard that drifts apart means one client is answering a different "
                        + "question from the other.")
                .isEqualTo(web);
        assertThat(mobile)
                .as("the self clause is what makes the field scope necessary, and taking it away "
                        + "would remove self-service rather than scope it")
                .contains("isSelfOrHasAnyOrgRole");
    }

    /** The keys the employee update switch names, read out of the method's own source. */
    private static Set<String> acceptedKeys() throws IOException {
        UpdateSurface surface = PartialUpdateSurfaces.surfaces().stream()
                .filter(candidate -> candidate.schema().equals(EmployeeUpdateFieldsDto.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the employee surface has gone from PartialUpdateSurfaces"));

        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = CASE_LABEL.matcher(PartialUpdateSurfaces.methodBody(surface));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertThat(keys)
                .as("no case labels found in %s; the method signature this test looks for has "
                        + "probably been reworded", surface.methodSignature())
                .isNotEmpty();
        return keys;
    }

    private static String patchGuard(String relativePath) throws IOException {
        Path source = SOURCE_ROOT.resolve(relativePath);
        assertThat(source).as("controller source, read relative to the project directory").exists();
        String text = Files.readString(source);

        Matcher matcher = PATCH_GUARD.matcher(text);
        assertThat(matcher.find())
                .as("no @PreAuthorize found on the PATCH {id} handler in %s; if the mapping or the "
                        + "annotation order has changed, update the pattern this test looks for",
                        source)
                .isTrue();
        return matcher.group(1);
    }
}
