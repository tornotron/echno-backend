package org.tornotron.echno_backend.modules.vision.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.domain.VisionOrgSettings;
import org.tornotron.echno_backend.modules.vision.dto.UpdateVisionSettingsRequest;
import org.tornotron.echno_backend.modules.vision.dto.VisionSettingsDto;
import org.tornotron.echno_backend.modules.vision.repository.CvModelRepository;
import org.tornotron.echno_backend.modules.vision.repository.VisionOrgSettingsRepository;
import org.tornotron.echno_backend.user.UserContextService;

/** The current organization's vision settings. No row reads as auto-inference off and every family. */
@Slf4j
@Service
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionSettingsService {

    private final VisionOrgSettingsRepository settings;
    private final CvModelRepository models;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    @Transactional(readOnly = true)
    public VisionSettingsDto get() {
        return settings.findById(TenantContext.getCurrentOrgId())
                .map(VisionSettingsService::toDto)
                .orElse(new VisionSettingsDto(false, List.of(), null, null));
    }

    /**
     * Replaces the settings. Every key must name a registered family; a family whose active version
     * is later retired is simply not run.
     *
     * @throws InvalidRequestException for a key that names no family
     */
    @Transactional
    public VisionSettingsDto update(UpdateVisionSettingsRequest req) {
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(req.modelKeys()));
        for (String key : keys) {
            if (models.findByModelKey(key).isEmpty()) {
                throw new InvalidRequestException("No model family with key '" + key + "' is registered");
            }
        }
        VisionOrgSettings row = settings.findById(TenantContext.getCurrentOrgId()).orElseGet(() -> {
            VisionOrgSettings created = new VisionOrgSettings();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            return created;
        });
        row.setAutoInfer(Boolean.TRUE.equals(req.autoInfer()));
        row.setModelKeys(keys);
        row.setUpdatedById(userContextService.getCurrentUserId());
        VisionOrgSettings saved = settings.saveAndFlush(row);
        log.info("Vision settings for organization {}: auto-inference {}, families {}", saved.getOrganizationId(),
                saved.isAutoInfer() ? "on" : "off", keys.isEmpty() ? "all" : keys);
        return toDto(saved);
    }

    /** Whether new evidence images of the current organization are run automatically, and with what. */
    @Transactional(readOnly = true)
    public Optional<List<String>> autoInferModelKeys() {
        return settings.findById(TenantContext.getCurrentOrgId())
                .filter(VisionOrgSettings::isAutoInfer)
                .map(s -> List.copyOf(s.getModelKeys()));
    }

    private static VisionSettingsDto toDto(VisionOrgSettings s) {
        return new VisionSettingsDto(s.isAutoInfer(), List.copyOf(s.getModelKeys()), s.getUpdatedById(), s.getUpdatedAt());
    }
}
