package org.tornotron.echno_backend.employee.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Schema(description = "Full view of an employee, covering personal details, employment, reporting line, "
        + "roles and status.")
@Data
// Both read the fields rather than the accessors, because getSalary() below answers for the
// caller on the current thread. Left on the getters, a test comparing two records would call it,
// and two records with different pay would be equal to a caller who may see neither.
@ToString(doNotUseGetters = true)
@EqualsAndHashCode(doNotUseGetters = true)
public class EmployeeDto {
    @Schema(description = "Unique employee id.", example = "7")
    private Long id;

    @Schema(description = "Organization-assigned employee code.", example = "EMP-0042")
    private String employeeId;

    @Schema(description = "Id of the organization the employee belongs to.", example = "3")
    private Long organizationId;

    @Schema(description = "Name of the organization the employee belongs to.", example = "Asset Homes")
    private String organizationName;

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Job title of the employee.", example = "Site Engineer")
    private String designation;

    @Schema(description = "Department the employee works in.", example = "Civil")
    private String department;

    @Schema(description = "Date the employee joined.", example = "2026-01-15T00:00:00")
    private LocalDateTime joiningDate;

    @Schema(description = "Gender of the employee.", example = "male")
    private String gender;

    @Schema(description = "Ten-digit contact phone number.", example = "9847012345")
    private String phoneNumber;

    @Schema(description = "Postal address of the employee.", example = "45 Anna Nagar, Chennai")
    private String address;

    @Schema(description = "Contact email address.", example = "ravi.kumar@example.com")
    private String emailAddress;

    @Schema(description = "Date of birth of the employee.", example = "1994-03-22T00:00:00")
    private LocalDateTime dateOfBirth;

    @Schema(description = "Blood group of the employee.", example = "O+")
    private String bloodGroup;

    @Schema(description = "Salary of the employee. Written only for a caller who administers "
            + "personnel in this tenant (system-admin or hr-admin) and on the caller's own record; "
            + "absent from the response otherwise.", example = "65000.0")
    private Double salary;

    @Schema(description = "Id of this employee's reporting manager.", example = "5")
    private Long managerId;

    @Schema(description = "Name of this employee's reporting manager.", example = "Priya Nair")
    private String managerName;

    @Schema(description = "Id of the structured shift timing assigned to the employee.", example = "5")
    private Long shiftTimingId;

    @Schema(description = "The resolved structured shift assigned to the employee. Null when unassigned.", nullable = true)
    private ShiftTimingDto shiftTiming;

    @Schema(description = "Employment status.", example = "ACTIVE")
    private EmployeeStatus status;

    @Schema(description = "Highest qualification held.", example = "B.E. Civil Engineering")
    private String qualification;

    @Schema(description = "Skills of the employee.", example = "[\"AutoCAD\", \"Estimation\"]")
    private List<String> skills;

    @Schema(description = "Certifications held by the employee.", example = "[\"OSHA\"]")
    private List<String> certifications;

    @Schema(description = "Years of experience.", example = "6")
    private Integer experience;

    @Schema(description = "URL of the employee's stored CV.", example = "https://cdn.echno.xyz/cv/emp-0042.pdf")
    private String cvUrl;

    @Schema(description = "Emergency contact details.", example = "Anita Kumar, 9847098765")
    private String emergencyContact;

    @Schema(description = "Platform-level user role.", example = "USER")
    private UserRole role;

    @Schema(description = "URL of the employee's profile picture.",
            example = "https://cdn.echno.xyz/avatars/emp-0042.png")
    private String profilePictureUrl;

    @Schema(description = "Organization roles granted to the employee.", example = "[\"project-manager\"]")
    private Set<OrgRole> orgRoles;

    @Schema(description = "Creation timestamp.", example = "2026-01-15T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp of the last update.", example = "2026-08-01T12:00:00")
    private LocalDateTime updatedAt;

    @Schema(description = "Files attached to the employee record.")
    private List<AttachmentDto> attachments;

    /**
     * The Keycloak subject of the user this employee record belongs to. Never published: it is
     * an internal identifier, and it is here so the record can say whose it is without a second
     * query. Kept out of the document by {@code @JsonIgnore} and out of the schema by
     * {@code @Schema(hidden = true)}.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private String userKeycloakId;

    /**
     * The salary, for a caller entitled to it, and nothing at all for a caller who is not. See
     * {@link EmployeeSalaryVisibility} for who is entitled and why.
     *
     * <p>The decision sits on the accessor rather than on an endpoint deliberately. This record is
     * returned from both employee controllers and is nested inside eleven other response types
     * (project, task, material, purchase order, goods received note, indent, site transfer,
     * material consumption, inventory transaction, payable, WBS element), each guarded for its own
     * resource and none of them for personnel data. A guard, a view or a second DTO would have to
     * be remembered at every one of those call sites and at the next one somebody adds. An
     * accessor cannot be forgotten: a new endpoint returning this type inherits the decision by
     * returning the type.
     *
     * <p>Withheld means absent rather than null, so the field reads the same as any other optional
     * one. {@code echno-core} parses it as a nullish number, so an absent salary needs no client
     * change.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Double getSalary() {
        return EmployeeSalaryVisibility.visibleTo(userKeycloakId) ? salary : null;
    }
}
