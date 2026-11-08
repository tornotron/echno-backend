package org.tornotron.echno_backend.modules.workprogress.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.WorkProgressModule;

/**
 * Adds lines to a sub-contract's bill of quantities from another module, with the same rules as the
 * BOQ endpoint: the same field limits, the same unique code per contract, and the amount worked out
 * as quantity times rate. Document ingestion writes accepted tender BOQ rows through this.
 *
 * <p>Guards in this codebase sit on controllers, so a caller must first ask {@link #WRITE_GUARD} for
 * the signed-in person, exactly as the endpoint would.
 */
public interface ContractBoqPort {

    /** The module that owns contract BOQs, for a registry check before writing. */
    String MODULE_ID = WorkProgressModule.ID;

    /** The guard on {@code POST /api/v1/contract-billing/contracts/{id}/boq-items}. */
    String WRITE_GUARD = WorkProgressModule.BILLING_SETUP_GUARD;

    /**
     * Adds one line to the contract's BOQ in the caller's transaction and organization.
     *
     * @return the new BOQ item's id
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException when a field breaks a
     *         limit of the endpoint, or the code is already on the contract's BOQ
     * @throws org.tornotron.echno_backend.common.exception.ResourceNotFoundException when the contract is not
     *         in the caller's organization
     */
    UUID addItem(Long subContractId, BoqLine line);

    /** One BOQ line of the caller's organization as it stands now, or empty. Read only. */
    Optional<BoqItemRef> findItem(UUID itemId);

    /**
     * Replaces one BOQ line's code, description, unit, quantity and rate in the caller's transaction, with
     * the update endpoint's rules: the same limits, the code unique on the contract, and a quantity no
     * lower than what is already certified. The line keeps its schedule link and position, whatever
     * {@code line.sortOrder()} says.
     *
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException when a rule is broken
     * @throws org.tornotron.echno_backend.common.exception.ResourceNotFoundException when the line is not in
     *         the caller's organization
     */
    void updateItem(UUID itemId, BoqLine line);

    /** The contract in the caller's organization, or empty. Read only. */
    Optional<ContractRef> findContract(Long subContractId);

    /**
     * One BOQ line.
     *
     * @param sortOrder Position in the BOQ, lower first, or null to leave it at the default
     */
    record BoqLine(String itemCode, String description, String unit, BigDecimal quantity, BigDecimal rate,
                   Integer sortOrder) {
    }

    /** A BOQ line as another module may see it. */
    record BoqItemRef(UUID id, Long subContractId, String itemCode, String description, String unit,
                      BigDecimal quantity, BigDecimal rate) {
    }

    /** A contract as another module may see it. */
    record ContractRef(Long id, Long projectId, String name) {
    }
}
