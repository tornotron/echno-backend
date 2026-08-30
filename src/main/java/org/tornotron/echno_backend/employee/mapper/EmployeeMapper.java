package org.tornotron.echno_backend.employee.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

/**
 * Maps {@link Employee} to its DTO. Most profile fields live on the associated
 * {@code User}, and the organization/manager associations are flattened to id + name.
 * The attachment list is signed through {@link AttachmentMapper}. Whether an employee
 * manages is read from {@code orgRoles}, which this copies straight across; there is no
 * separate flag to populate.
 */
@Mapper(componentModel = "spring", uses = {AttachmentMapper.class, ShiftTimingMapper.class})
public interface EmployeeMapper {

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
    EmployeeDto toDto(Employee employee);
}
