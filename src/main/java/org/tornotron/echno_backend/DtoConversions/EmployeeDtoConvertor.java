package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

@Component
public class EmployeeDtoConvertor {

    public static EmployeeDto convertEmployeeToDto(Employee employee) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(employee.getId());
        dto.setSalary(employee.getSalary());
        dto.setEmployeeName(employee.getEmployeeName());
        dto.setAddress(employee.getAddress());
        dto.setDesignation(employee.getDesignation());
        dto.setDepartment(employee.getDepartment());
        dto.setJoiningDate(employee.getJoiningDate());
        dto.setReportingManager(employee.getReportingManager());
        dto.setShiftTiming(employee.getShiftTiming());
        dto.setStatus(employee.getStatus());
        dto.setSalary(employee.getSalary());
        dto.setGender(employee.getGender());
        dto.setEmailAddress(employee.getEmailAddress());
        dto.setPhoneNumber(employee.getPhoneNumber());
        dto.setDateOfBirth(employee.getDateOfBirth());
        dto.setBloodGroup(employee.getUser().getBloodGroup());
        dto.setQualification(employee.getUser().getQualification());
        dto.setSkills(employee.getUser().getSkills());
        dto.setExperience(employee.getUser().getExperience());
        dto.setCvUrl(employee.getUser().getCvUrl());
        dto.setEmergencyContact(employee.getUser().getEmergencyContact());
        dto.setRole(employee.getUser().getRole());
        dto.setProfilePictureUrl(employee.getUser().getProfilePictureUrl());
        dto.setCreatedAt(employee.getUser().getCreatedAt());
        dto.setUpdatedAt(employee.getUser().getUpdatedAt());
        return dto;
    }


}
