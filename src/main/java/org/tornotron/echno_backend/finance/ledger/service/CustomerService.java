package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.CustomerDto;
import org.tornotron.echno_backend.finance.ledger.dtos.UpdateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.CustomerMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repo;
    private final CustomerMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public Page<CustomerDto> search(String name, Pageable pageable) {
        return repo.findByNameContainingIgnoreCaseAndActive(
                name == null ? "" : name, true, pageable
        ).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public CustomerDto findById(UUID id) {
        return mapper.toDto(getEntity(id));
    }

    @Transactional
    public CustomerDto create(CreateCustomerRequest req) {
        if(repo.existsByCode(req.getCode())) {
            throw new InvalidJournalException("Customer code '" + req.getCode() + "' is already in use in this organization");
        }
        Customer customer = new Customer();
        customer.setCode(req.getCode());
        customer.setName(req.getName());
        customer.setGstin(req.getGstin());
        customer.setPan(req.getPan());
        customer.setEmail(req.getEmail());
        customer.setPhone(req.getPhone());
        if (req.getBillingAddress() != null) customer.setBillingAddress(mapper.toAddress(req.getBillingAddress()));
        customer.setCreditLimit(req.getCreditLimit());
        customer.setPaymentTermsDays(req.getPaymentTermsDays() == null ? 30 : req.getPaymentTermsDays());
        customer.setActive(true);
        customer.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return mapper.toDto(repo.save(customer));
    }

    @Transactional
    public CustomerDto update(UUID id, UpdateCustomerRequest req) {
        Customer customer = getEntity(id);
        customer.setName(req.getName());
        customer.setGstin(req.getGstin());
        customer.setPan(req.getPan());
        customer.setEmail(req.getEmail());
        customer.setPhone(req.getPhone());
        if(req.getBillingAddress() != null) customer.setBillingAddress(mapper.toAddress(req.getBillingAddress()));
        customer.setCreditLimit(req.getCreditLimit());
        if(req.getPaymentTermsDays() != null) customer.setPaymentTermsDays(req.getPaymentTermsDays());
        return mapper.toDto(customer);
    }

    @Transactional
    public void deactivate(UUID id) {
        getEntity(id).setActive(false);
    }

    Customer getEntity(UUID id) {
        return repo.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer with ID " + id + " was not found in this organization"));
    }
}
