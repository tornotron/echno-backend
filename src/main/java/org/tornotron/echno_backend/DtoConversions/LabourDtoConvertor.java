package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.labour.Labour;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.dto.LabourSimpleDto;
import org.tornotron.echno_backend.labour.enums.EmploymentType;

@Component
public class LabourDtoConvertor {

    public static LabourSimpleDto convertLabourToSimpleDto(Labour labour) {
        LabourSimpleDto dto = new LabourSimpleDto();
        dto.setId(labour.getId());
        dto.setLabourId(labour.getLabourID());
        dto.setOrganizationId(labour.getOrganization().getId());
        dto.setOrganizationName(labour.getOrganization().getOrganizationName());
        dto.setCurrentProjectId(labour.getCurrentProject().getId().toString());
        dto.setCurrentProjectName(labour.getCurrentProject().getProjectName());
        dto.setFullName(labour.getFullName());
        dto.setEmail(labour.getEmail());
        dto.setAddress(labour.getAddress());
        dto.setPhoneNumber(labour.getPhoneNumber());
        dto.setEmergencyContactName(labour.getEmergencyContactName());
        dto.setEmergencyContactNumber(labour.getEmergencyContactNumber());
        dto.setSpecialization(labour.getSpecialization());
        dto.setEmploymentType(labour.getEmploymentType().toString());
        dto.setSkillLevel(labour.getSkillLevel().toString());
        dto.setStatus(labour.getStatus().toString());
        dto.setJoiningDate(labour.getJoiningDate());
        dto.setDailyRate(labour.getDailyRate());
        dto.setOverTimeRate(labour.getOverTimeRate());
        dto.setBankAccountNumber(labour.getBankAccountNumber());
        dto.setBankName(labour.getBankName());
        dto.setIfscCode(labour.getIfscCode());
        dto.setAdditionalNotes(labour.getAdditionalNotes());
        return dto;
    }

    public static LabourDto convertLabourToDto(Labour labour) {

        LabourDto dto = new LabourDto();
        dto.setId(labour.getId());
        dto.setLabourID(labour.getLabourID());
        dto.setFullName(labour.getFullName());
        dto.setEmail(labour.getEmail());
        dto.setAddress(labour.getAddress());
        dto.setPhoneNumber(labour.getPhoneNumber());
        dto.setEmergencyContactName(labour.getEmergencyContactName());
        dto.setEmergencyContactNumber(labour.getEmergencyContactNumber());
        dto.setSpecialization(labour.getSpecialization());
        dto.setEmploymentType(labour.getEmploymentType());
        dto.setSkillLevel(labour.getSkillLevel());
        dto.setStatus(labour.getStatus());
        dto.setJoiningDate(labour.getJoiningDate());
        dto.setDailyRate(labour.getDailyRate());
        dto.setOverTimeRate(labour.getOverTimeRate());
        dto.setBankAccountNumber(labour.getBankAccountNumber());
        dto.setBankName(labour.getBankName());
        dto.setIfscCode(labour.getIfscCode());
        dto.setAdditionalNotes(labour.getAdditionalNotes());
        return dto;
    }
}
