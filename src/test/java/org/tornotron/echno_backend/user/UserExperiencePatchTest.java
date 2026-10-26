package org.tornotron.echno_backend.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.mapper.UserMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Experience in a user patch is read through {@link Number}: Jackson gives a value written as
 * 4.0, or one past the int range, as Double or Long, and the old {@code (Integer)} cast threw
 * ClassCastException on both.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserExperiencePatchTest {

    @Mock private UserRepository userRepository;
    @Mock private KeycloakUserProvisioningService keycloakUserProvisioningService;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrganizationMapper organizationMapper;

    @InjectMocks private UserService service;

    private User storedUser() {
        User user = new User();
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        return user;
    }

    @Test
    @DisplayName("experience sent as 4.0 is applied as 4")
    void experienceAsDouble_isApplied() {
        User user = storedUser();

        service.partialUpdateAnUser(Map.of("experience", 4.0), 3L);

        assertThat(user.getExperience()).isEqualTo(4);
    }

    @Test
    @DisplayName("a fractional or out-of-range experience is refused, not truncated")
    void nonWholeExperience_isRefused() {
        User user = storedUser();
        user.setExperience(2);

        assertThatThrownBy(() -> service.partialUpdateAnUser(Map.of("experience", 4.9), 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.partialUpdateAnUser(Map.of("experience", 3_000_000_000L), 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getExperience()).isEqualTo(2);
    }
}
