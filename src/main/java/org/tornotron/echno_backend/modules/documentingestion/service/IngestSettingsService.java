package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestOrgSettings;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestSettingChange;
import org.tornotron.echno_backend.modules.documentingestion.dto.IngestSettingsDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestOrgSettingsRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestSettingChangeRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The organization's switch for reading tender text with the outside AI service. On by default (the
 * product owner's decision of 8 Nov 2026): an organization with no settings row reads as on, and its
 * {@code system-admin} can turn it off. Every change is kept, with who made it and when.
 *
 * <p>The switch decides only whether LLM extraction may run for the organization. The operator switch
 * and the allow-list of section 6.7 still come first, so no text leaves Echno for an organization the
 * module is not open to, whatever this says.
 */
@Service
public class IngestSettingsService {

    public static final boolean LLM_READING_DEFAULT = true;

    public static final String LLM_READING_DISCLOSURE = "When this is on, the text of tender files uploaded here is sent "
            + "to the AI service Echno is set up with, so it can be read into values for you to check. The service states "
            + "that it does not keep the text or train on it. Each call is recorded with the file and pages it sent. Excel "
            + "BOQs and the document list work with this off.";

    static final int RECENT_CHANGES = 20;

    private final IngestOrgSettingsRepository settings;
    private final IngestSettingChangeRepository changes;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public IngestSettingsService(IngestOrgSettingsRepository settings, IngestSettingChangeRepository changes,
                                 TenantEntityHelper tenantEntityHelper, UserContextService userContextService) {
        this.settings = settings;
        this.changes = changes;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    /** Whether LLM reading is on for the organization: the stored choice, or on when none was made. */
    @Transactional(readOnly = true)
    public boolean isLlmReadingOn(Long organizationId) {
        return settings.findForOrganization(organizationId).map(IngestOrgSettings::isLlmReadingEnabled)
                .orElse(LLM_READING_DEFAULT);
    }

    @Transactional(readOnly = true)
    public IngestSettingsDto get() {
        Long orgId = TenantContext.getCurrentOrgId();
        IngestOrgSettings row = settings.findForOrganization(orgId).orElse(null);
        List<IngestSettingsDto.Change> recent = changes.findRecent(orgId, PageRequest.of(0, RECENT_CHANGES)).stream()
                .map(c -> new IngestSettingsDto.Change(c.getSetting(), c.getOldValue(), c.getNewValue(),
                        c.getChangedBy(), c.getChangedAt()))
                .toList();
        return new IngestSettingsDto(row == null ? LLM_READING_DEFAULT : row.isLlmReadingEnabled(),
                LLM_READING_DISCLOSURE, row == null ? null : row.getChangedBy(), row == null ? null : row.getChangedAt(),
                recent);
    }

    /** Turns LLM reading on or off and records the change; setting the value it already has records nothing. */
    @Transactional
    public IngestSettingsDto setLlmReading(boolean enabled) {
        Long orgId = TenantContext.getCurrentOrgId();
        IngestOrgSettings row = settings.findForOrganization(orgId).orElseGet(() -> {
            IngestOrgSettings fresh = new IngestOrgSettings();
            fresh.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            fresh.setLlmReadingEnabled(LLM_READING_DEFAULT);
            return fresh;
        });
        boolean before = row.isLlmReadingEnabled();
        if (before != enabled || row.getId() == null) {
            Long userId = userContextService.getCurrentUserId();
            LocalDateTime now = LocalDateTime.now();
            row.setLlmReadingEnabled(enabled);
            if (before != enabled) {
                row.setChangedBy(userId);
                row.setChangedAt(now);
                IngestSettingChange change = new IngestSettingChange();
                change.setOrganization(row.getOrganization());
                change.setSetting(IngestSettingChange.LLM_READING);
                change.setOldValue(Boolean.toString(before));
                change.setNewValue(Boolean.toString(enabled));
                change.setChangedBy(userId);
                change.setChangedAt(now);
                changes.save(change);
            }
            settings.save(row);
        }
        return get();
    }
}
