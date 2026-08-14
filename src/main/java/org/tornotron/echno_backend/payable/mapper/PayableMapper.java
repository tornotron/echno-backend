package org.tornotron.echno_backend.payable.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.payable.dto.PayableDto;

/** Maps {@link Payable} to its DTO. Vendor/GRN/project flatten to id + name; createdBy via {@link EmployeeMapper}. */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface PayableMapper {

    @Mapping(source = "vendor.id", target = "vendorId")
    @Mapping(source = "vendor.vendorName", target = "vendorName")
    @Mapping(source = "goodsReceivedNote.id", target = "goodsReceivedNoteId")
    @Mapping(source = "goodsReceivedNote.grnNumber", target = "grnNumber")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    PayableDto toDto(Payable payable);
}
