package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.intend.dto.IntendDto;

@Component
public class IntendDtoConvertor {

    public static IntendDto convertIntendToDto(Intend intend) {
        IntendDto dto = new IntendDto();
        dto.setId(intend.getId());
        dto.setCreatedBy(UserDtoConvertor.convertUserToDto(intend.getCreatedBy()));
        dto.setIntendNumber(intend.getIntendNumber());
        dto.setCreatedAt(intend.getCreatedAt());
        dto.setStatus(intend.getStatus());
        dto.setExpectedOn(intend.getExpectedOn());
        dto.setRemarks(intend.getRemarks());
        dto.setItems(intend.getItems());
        return dto;
    }
}
