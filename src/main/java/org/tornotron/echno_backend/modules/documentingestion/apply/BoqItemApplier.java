package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqCandidates;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;

/**
 * Writes accepted BOQ rows to the contract the package names, through the Work Progress module's
 * BOQ port, under that endpoint's own guard and limits. A package with no contract keeps its accepted
 * rows as its tender BOQ, and a tenant without Work Progress gets one clear refusal. Rows land in the
 * workbook's order: sheet first, then row.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class BoqItemApplier implements IngestionApplier {

    public static final String TARGET_TYPE = "CONTRACT_BOQ_ITEM";
    /** Rows a sheet may hold before the next sheet's order begins. */
    static final int ROWS_PER_SHEET_ORDER = 1_000_000;

    private final IngestPackageRepository packages;
    private final ContractBoqPort contractBoq;
    private final ModuleRegistry modules;

    public BoqItemApplier(IngestPackageRepository packages, ContractBoqPort contractBoq, ModuleRegistry modules) {
        this.packages = packages;
        this.contractBoq = contractBoq;
        this.modules = modules;
    }

    // The two lookups below are metadata that ApplyService reads while the context starts, with no
    // one signed in. They write nothing, so they open the class-level guard; every write keeps it.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.BOQ_ITEM;
    }

    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return ContractBoqPort.WRITE_GUARD;
    }

    @Override
    public void preflight(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        if (p.getSubContractId() == null) {
            throw new InvalidRequestException("Choose the sub-contract this BOQ is for first. Until then the accepted "
                    + "rows stay on the package as its tender BOQ.");
        }
        if (!modules.isEnabledForOrg(ContractBoqPort.MODULE_ID, TenantContext.getCurrentOrgId())) {
            throw new InvalidRequestException("Contract billing is not enabled for this organization, so the BOQ "
                    + "cannot be written to a contract. The accepted rows stay on the package.");
        }
        if (contractBoq.findContract(p.getSubContractId()).isEmpty()) {
            throw new ResourceNotFoundException("The package's sub-contract " + p.getSubContractId() + " no longer exists");
        }
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        IngestPackage p = requirePackage(candidate.getPackageId());
        if (p.getSubContractId() == null) {
            throw new InvalidRequestException("The package has no sub-contract to write the BOQ to");
        }
        ContractBoqPort.BoqLine line = new ContractBoqPort.BoqLine(
                text(accepted.get(BoqField.ITEM_CODE.key())),
                text(accepted.get(BoqField.DESCRIPTION.key())),
                text(accepted.get(BoqField.UNIT.key())),
                number(accepted.get(BoqField.QUANTITY.key()), "quantity"),
                number(accepted.get(BoqField.RATE.key()), "rate"),
                sortOrder(accepted));
        UUID itemId = contractBoq.addItem(p.getSubContractId(), line);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(BoqField.ITEM_CODE.key(), "itemCode");
        fields.put(BoqField.DESCRIPTION.key(), "description");
        fields.put(BoqField.UNIT.key(), "unit");
        fields.put(BoqField.QUANTITY.key(), "contractQuantity");
        fields.put(BoqField.RATE.key(), "rate");
        return new AppliedTarget(TARGET_TYPE, itemId.toString(), fields);
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    static Integer sortOrder(Map<String, Object> payload) {
        Object sheet = payload.get(BoqCandidates.SHEET_INDEX);
        Object row = payload.get(BoqCandidates.ROW);
        if (!(sheet instanceof Number s) || !(row instanceof Number r)) {
            return null;
        }
        long order = (s.longValue() + 1) * ROWS_PER_SHEET_ORDER + r.longValue();
        return order > Integer.MAX_VALUE ? null : (int) order;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().strip();
    }

    static BigDecimal number(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().strip().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("The " + field + " \"" + value + "\" is not a number");
        }
    }
}
