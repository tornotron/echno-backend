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
    private final Validator validator = VALIDATORS.getValidator();

    public ContractBoqPortAdapter(ContractBillingService billing, SubContractRepository subContracts) {
        this.billing = billing;
        this.subContracts = subContracts;
    }

    @Override
    public UUID addItem(Long subContractId, BoqLine line) {
        BoqItemRequest req = new BoqItemRequest(line.itemCode(), line.description(), line.unit(), line.quantity(),
                line.rate(), null, line.sortOrder());
        Set<ConstraintViolation<BoqItemRequest>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
        return billing.addBoqItem(subContractId, req).id();
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
