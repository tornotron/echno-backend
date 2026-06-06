package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
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
        if(repo.existsByCode(req.code())) {
            throw new InvalidJournalException("Customer code already exists: "+ req.code());
        }
        Customer customer = new Customer();
        customer.setCode(req.code());
        customer.setName(req.name());
        customer.setGstin(req.gstin());
        customer.setPan(req.pan());
        customer.setEmail(req.email());
        customer.setPhone(req.phone());
        if (req.billingAddress() != null) customer.setBillingAddress(mapper.toAddress(req.billingAddress()));
        customer.setCreditLimit(req.creditLimit());
        customer.setPaymentTermsDays(req.paymentTermsDays() == null ? 30 : req.paymentTermsDays());
        customer.setActive(true);
        return mapper.toDto(repo.save(customer));
    }

    @Transactional
    public CustomerDto update(UUID id, UpdateCustomerRequest req) {
        Customer customer = getEntity(id);
        customer.setName(req.name());
        customer.setGstin(req.gstin());
        customer.setPan(req.pan());
        customer.setEmail(req.email());
        customer.setPhone(req.phone());
        if(req.billingAddress() != null) customer.setBillingAddress(mapper.toAddress(req.billingAddress()));
        customer.setCreditLimit(req.creditLimit());
        if(req.paymentTermsDays() != null) customer.setPaymentTermsDays(req.paymentTermsDays());
        return mapper.toDto(customer);
    }

    @Transactional
    public void deactivate(UUID id) {
        getEntity(id).setActive(false);
    }

    Customer getEntity(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: "+ id));
    }
}
