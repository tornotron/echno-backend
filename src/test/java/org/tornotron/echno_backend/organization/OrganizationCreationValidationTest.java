package org.tornotron.echno_backend.organization;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.UserContextService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Both organization controllers take the create payload as the JSON string part of a multipart
 * request and deserialize it by hand, so Spring never binds a bean and never validates one. The
 * ten constraints on {@link OrganizationCreationDto} were therefore decorative. These pin that
 * the service runs them itself, and that a rejected payload never reaches the repository.
 *
 * <p>A real Hibernate Validator with mocked collaborators: whether the constraints fire needs a
 * genuine validator, but no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationCreationValidationTest {

    private static ValidatorFactory factory;
    private Validator validator;

    @Mock
    private OrganizationRepository repository;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private KeycloakGroupService keycloakGroupService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private UserContextService userContextService;
    @Mock
    private EmployeeService employeeService;
    @Mock
    private OrganizationMapper organizationMapper;
    @Mock
    private OrganizationSecurityService orgSecurity;
    @Mock
    private OrganizationOnboardingSeeder onboardingSeeder;

    private OrganizationService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        service = new OrganizationService(repository, attachmentService, fileStorageService,
                keycloakGroupService, subscriptionService, userContextService, employeeService,
                organizationMapper, orgSecurity, onboardingSeeder, new PayloadValidator(validator));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private OrganizationCreationDto validDto() {
        OrganizationCreationDto dto = new OrganizationCreationDto();
        dto.setOrganizationName("Meridian Builders");
        dto.setOrganizationAddress("Plot 47, Sector 18, Gurugram");
        dto.setOrganizationEmail("site@meridianbuilders.com");
        dto.setOrganizationPhone("+919876543210");
        return dto;
    }

    @Test
    void addOrganization_rejectsABlankName_beforeTouchingTheRepository() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationName("  ");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(repository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addOrganization_rejectsAMalformedEmail() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationEmail("site-at-meridianbuilders");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(repository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addOrganization_rejectsAMissingEmail() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationEmail(null);

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_rejectsABlankAddress() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationAddress("");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_rejectsAMissingPhone() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationPhone(null);

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_rejectsAWebsiteOverTheLimit() {
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationWebsite("https://" + "x".repeat(250) + ".com");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_acceptsATwoLetterName() {
        // The old minimum of three refused "3M", "HP" and "GE"; the form has never had one.
        // Failing past validation means it reached the duplicate-email check, which is mocked
        // here; what matters is that it is not a constraint failure.
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationName("3M");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_acceptsAFullPostalAddress() {
        // Sixty-seven characters, which the old cap of fifty would have refused.
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationAddress(
                "Plot 47, Sector 18, Udyog Vihar Phase IV, Gurugram, Haryana 122015");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_acceptsALongTopLevelDomain() {
        // The old pattern capped the top-level domain at six characters, refusing exactly the
        // domains this product's customers register.
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationEmail("site@meridian.construction");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addOrganization_acceptsAFifteenDigitInternationalNumber() {
        // E.164 allows fifteen digits, and the form sends the leading plus, so the string is
        // sixteen characters. The old maximum of fifteen refused it.
        OrganizationCreationDto dto = validDto();
        dto.setOrganizationPhone("+123456789012345");

        assertThatThrownBy(() -> service.addOrganization(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }
}
