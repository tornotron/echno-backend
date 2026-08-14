package org.tornotron.echno_backend.user.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.dto.UserDto;

/** Maps {@link User} to its DTO. All fields map by name; attachments are signed through {@link AttachmentMapper}. */
@Mapper(componentModel = "spring", uses = AttachmentMapper.class)
public interface UserMapper {

    UserDto toDto(User user);
}
