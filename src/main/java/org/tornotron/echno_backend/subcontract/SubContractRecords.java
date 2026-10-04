package org.tornotron.echno_backend.subcontract;

import java.util.Collection;

/**
 * Something that keeps records against a sub-contract or its milestones and so must outlive
 * them, for example a module's bills. The sub-contract service asks every bean of this type
 * before it deletes a contract or drops one of its milestones, and refuses when one of them
 * still holds records.
 *
 * <p>The sub-contract is core and cannot see a module's tables, so a module that records against
 * contracts implements this interface instead of leaving the delete to fail on a foreign key.
 */
public interface SubContractRecords {

    /**
     * @param subContractId the contract being deleted, in the current tenant
     * @return a short, readable name for the records held against it (for example "bills"), or
     *         null when there are none
     */
    String recordsHeldAgainstContract(Long subContractId);

    /**
     * @param milestoneIds milestones about to be removed from their contract, in the current tenant
     * @return a short, readable name for the records held against any of them, or null when there
     *         are none
     */
    String recordsHeldAgainstMilestones(Collection<Long> milestoneIds);
}
