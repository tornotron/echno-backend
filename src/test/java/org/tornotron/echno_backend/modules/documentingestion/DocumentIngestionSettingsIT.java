package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.llm.LlmEndpointProperties;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestSettingChange;
import org.tornotron.echno_backend.modules.documentingestion.dto.IngestSettingsDto;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Reading tender text with the outside AI service is on by default and an admin can turn it off, per
 * organization, with each change recorded. Product owner's decision of 8 Nov 2026; design note
 * sections 6.7 (item 4) and 7.4.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IngestSettingsService.class, TenantEntityHelper.class, LlmEndpointProperties.class})
@TestPropertySource(properties = {
        "compliance.ai.provider-name=Example Inference Cloud",
        "compliance.ai.provider-privacy-url=https://inference.example.test/privacy",
        "compliance.ai.model=example-model-7b"
})
class DocumentIngestionSettingsIT extends AbstractIntegrationTest {

    static final long ADMIN = 4242L;

    @Autowired
    IngestSettingsService settings;

    @Autowired
    OrganizationRepository organizations;

    @MockitoBean
    UserContextService userContext;

    Long orgA;
    Long orgB;

    @BeforeEach
    void twoOrganizations() {
        orgA = organization();
        orgB = organization();
        TenantContext.setCurrentOrgId(orgA);
        when(userContext.getCurrentUserId()).thenReturn(ADMIN);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void anOrganizationThatChoseNothingHasLlmReadingOn() {
        assertThat(IngestSettingsService.LLM_READING_DEFAULT).isTrue();
        assertThat(settings.isLlmReadingOn(orgA)).isTrue();
        IngestSettingsDto dto = settings.get();
        assertThat(dto.llmReadingEnabled()).isTrue();
        assertThat(dto.changedBy()).isNull();
        assertThat(dto.recentChanges()).isEmpty();
        assertThat(dto.llmReadingDisclosure()).contains("sent to the AI service", "does not keep the text");
        assertThat(dto.aiProvider()).isEqualTo("Example Inference Cloud");
        assertThat(dto.aiModel()).isEqualTo("example-model-7b");
        assertThat(dto.aiProviderPrivacyUrl()).isEqualTo("https://inference.example.test/privacy");
    }

    @Test
    void anAdminTurnsItOffForTheirOrganizationOnlyAndTheChangeIsRecorded() {
        IngestSettingsDto off = settings.setLlmReading(false);

        assertThat(off.llmReadingEnabled()).isFalse();
        assertThat(off.changedBy()).isEqualTo(ADMIN);
        assertThat(off.recentChanges()).singleElement().satisfies(c -> {
            assertThat(c.setting()).isEqualTo(IngestSettingChange.LLM_READING);
            assertThat(c.oldValue()).isEqualTo("true");
            assertThat(c.newValue()).isEqualTo("false");
            assertThat(c.changedBy()).isEqualTo(ADMIN);
            assertThat(c.changedAt()).isNotNull();
        });
        assertThat(settings.isLlmReadingOn(orgA)).isFalse();
        assertThat(settings.isLlmReadingOn(orgB)).as("another organization keeps the default").isTrue();
    }

    @Test
    void turningItBackOnIsRecordedAndRepeatingAValueRecordsNothing() {
        settings.setLlmReading(false);
        settings.setLlmReading(false);
        IngestSettingsDto on = settings.setLlmReading(true);

        assertThat(on.llmReadingEnabled()).isTrue();
        assertThat(on.recentChanges()).extracting(IngestSettingsDto.Change::newValue).containsExactlyInAnyOrder("false", "true");
        assertThat(settings.isLlmReadingOn(orgA)).isTrue();
    }

    @Test
    void settingTheDefaultExplicitlyRecordsNoChange() {
        IngestSettingsDto on = settings.setLlmReading(true);

        assertThat(on.llmReadingEnabled()).isTrue();
        assertThat(on.recentChanges()).isEmpty();
    }

    private Long organization() {
        Organization org = new Organization();
        org.setOrganizationName("Settings " + UUID.randomUUID());
        org.setOrganizationAddress("address");
        org.setOrganizationEmail(UUID.randomUUID() + "@example.test");
        org.setOrganizationPhone("0000000000");
        return organizations.save(org).getId();
    }
}
