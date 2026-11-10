package org.tornotron.echno_backend.employee.mapper;

import java.util.EnumSet;
import java.util.Set;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.modules.access.api.AccessRoleHolders;

/**
 * Maps {@link Employee} to its DTO. Most profile fields live on the associated
 * {@code User}, and the organization/manager associations are flattened to id + name.
 * The attachment list is signed through {@link AttachmentMapper}. {@code orgRoles} is read from
 * the role assignments: the old roles whose starting roles the employee holds across the
 * organization, which is what web and core still gate on.
 */
@Mapper(componentModel = "spring", uses = {AttachmentMapper.class, ShiftTimingMapper.class})
public abstract class EmployeeMapper {

    private AccessRoleHolders roleHolders;

    @Autowired
    public void setRoleHolders(AccessRoleHolders roleHolders) {
        this.roleHolders = roleHolders;
    }


    @Mapping(source = "shiftTiming.id", target = "shiftTimingId")
    @Mapping(source = "shiftTiming", target = "shiftTiming")
    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(source = "organization.organizationName", target = "organizationName")
    @Mapping(source = "manager.id", target = "managerId")
    @Mapping(source = "manager.employeeName", target = "managerName")
    @Mapping(source = "user.address", target = "address")
    @Mapping(source = "user.bloodGroup", target = "bloodGroup")
    @Mapping(source = "user.qualification", target = "qualification")
    @Mapping(source = "user.skills", target = "skills")
    @Mapping(source = "user.certifications", target = "certifications")
    @Mapping(source = "user.experience", target = "experience")
    @Mapping(source = "user.cvUrl", target = "cvUrl")
    @Mapping(source = "user.emergencyContact", target = "emergencyContact")
    @Mapping(source = "user.role", target = "role")
    @Mapping(source = "user.profilePictureUrl", target = "profilePictureUrl")
    @Mapping(source = "user.createdAt", target = "createdAt")
    @Mapping(source = "user.updatedAt", target = "updatedAt")
    @Mapping(source = "user.attachments", target = "attachments")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(target = "orgRoles", ignore = true)
    public abstract EmployeeDto toDto(Employee employee);

    @AfterMapping
    protected void heldOrgRoles(Employee employee, @MappingTarget EmployeeDto dto) {
        dto.setOrgRoles(orgRolesOf(employee));
    }

    /** The old roles the employee holds through their starting roles, in declaration order. */
    public Set<OrgRole> orgRolesOf(Employee employee) {
        Set<OrgRole> held = EnumSet.noneOf(OrgRole.class);
        if (employee.getId() == null || employee.getOrganization() == null) {
            return held;
        }
        Set<String> names = roleHolders.oldRolesOf(employee.getOrganization().getId(), employee.getId());
        for (OrgRole role : OrgRole.values()) {
            if (names.contains(role.getGroupName())) {
                held.add(role);
            }
        }
        return held;
    }
}
