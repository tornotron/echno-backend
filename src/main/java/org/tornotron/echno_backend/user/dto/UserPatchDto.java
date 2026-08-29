package org.tornotron.echno_backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "A single user's partial update within a batch: the user id and the fields to "
        + "change on them.")
@Data
public class UserPatchDto {

    @Schema(description = "Id of the user to update.", example = "31")
    private Long id;

    @Schema(implementation = UserUpdateFieldsDto.class)
    private Map<String, Object> updates;
}
