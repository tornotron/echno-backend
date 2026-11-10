package org.tornotron.echno_backend.modules.workprogress.billing.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBoqItem;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBoqItemRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.subcontract.SubContractRepository;

/**
 * The contract BOQ port, over the same service the BOQ endpoint calls. Each line is checked against
 * the endpoint's own request constraints first, so a line the endpoint would refuse is refused here
 * with the same limits.
 */
@Service
public class ContractBoqPortAdapter implements ContractBoqPort {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final ContractBillingService billing;
    private final SubContractRepository subContracts;
    private final ContractBoqItemRepository boqItems;
    private final Validator validator = VALIDATORS.getValidator();

    public ContractBoqPortAdapter(ContractBillingService billing, SubContractRepository subContracts,
                                  ContractBoqItemRepository boqItems) {
        this.billing = billing;
        this.subContracts = subContracts;
        this.boqItems = boqItems;
    }

    @Override
    public UUID addItem(Long subContractId, BoqLine line) {
        BoqItemRequest req = new BoqItemRequest(line.itemCode(), line.description(), line.unit(), line.quantity(),
                line.rate(), null, line.sortOrder());
        validate(req);
        return billing.addBoqItem(subContractId, req).id();
    }

    private void validate(BoqItemRequest req) {
        Set<ConstraintViolation<BoqItemRequest>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
    }

    @Override
    public Optional<BoqItemRef> findItem(UUID itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return boqItems.findScoped(itemId, TenantContext.getCurrentOrgId())
                .map(i -> new BoqItemRef(i.getId(), i.getSubContractId(), i.getItemCode(), i.getDescription(), i.getUnit(),
                        i.getContractQuantity(), i.getRate()));
    }

    @Override
    public void updateItem(UUID itemId, BoqLine line) {
        ContractBoqItem item = boqItems.findScoped(itemId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("BOQ item not found: " + itemId));
        BoqItemRequest req = new BoqItemRequest(line.itemCode(), line.description(), line.unit(), line.quantity(),
                line.rate(), item.getWbsElementId(), item.getSortOrder());
        validate(req);
        billing.updateBoqItem(itemId, req);
    }

    @Override
    public Optional<ContractRef> findContract(Long subContractId) {
        if (subContractId == null) {
            return Optional.empty();
        }
        return subContracts.findByIdAndOrganization_Id(subContractId, TenantContext.getCurrentOrgId())
                .map(c -> new ContractRef(c.getId(), c.getProjectId(), c.getContractName()));
    }
}
