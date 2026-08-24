package org.tornotron.echno_backend.finance.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.settings.dtos.FinanceSettingsDto;
import org.tornotron.echno_backend.finance.settings.dtos.UpdateFinanceSettingsRequest;

import java.math.BigDecimal;

/**
 * Manages the single finance-settings row per organization.
 *
 * <p>Follows the get-or-create idiom: the first read for a tenant materializes a default row with
 * no threshold set (every invoice needs approval), and later reads and the update work against that
 * same row. All access is scoped to the current tenant.
 */
@Service
@RequiredArgsConstructor
public class FinanceSettingsService {

    private final FinanceSettingsRepository repository;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Returns the finance settings for the current tenant, creating the row with defaults on first
     * access.
     */
    @Transactional
    public FinanceSettings getOrCreate() {
        Long orgId = TenantContext.getCurrentOrgId();
        return repository.findByOrganization_Id(orgId)
                .orElseGet(() -> {
                    FinanceSettings settings = new FinanceSettings();
                    settings.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                    settings.setApprovalThreshold(null);
                    return repository.save(settings);
                });
    }

    /** The current tenant's finance settings as a DTO. */
    @Transactional
    public FinanceSettingsDto getSettings() {
        return toDto(getOrCreate());
    }

    /**
     * The current tenant's auto-approval threshold, or null when every invoice needs manual
     * approval. Does not create a row when none exists yet.
     */
    @Transactional(readOnly = true)
    public BigDecimal getApprovalThreshold() {
        return repository.findByOrganization_Id(TenantContext.getCurrentOrgId())
                .map(FinanceSettings::getApprovalThreshold)
                .orElse(null);
    }

    /** Upserts the finance settings for the current tenant from the request. */
    @Transactional
    public FinanceSettingsDto update(UpdateFinanceSettingsRequest request) {
        FinanceSettings settings = getOrCreate();
        settings.setApprovalThreshold(request.approvalThreshold());
        return toDto(repository.save(settings));
    }

    private FinanceSettingsDto toDto(FinanceSettings settings) {
        return new FinanceSettingsDto(settings.getApprovalThreshold());
    }
}
