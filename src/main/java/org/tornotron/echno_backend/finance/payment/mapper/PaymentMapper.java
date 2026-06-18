package org.tornotron.echno_backend.finance.payment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.payment.domain.Payment;
import org.tornotron.echno_backend.finance.payment.domain.PaymentAllocation;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(source = "customer.id", target = "customerId")
    @Mapping(source = "customer.name",     target = "customerName")
    @Mapping(source = "companyBankAccount.id",    target = "companyBankAccountId")
    @Mapping(source = "companyBankAccount.bankName",  target = "bankName")
    @Mapping(source = "companyBankAccount.accountNumber",  target = "bankAccountNumber")
    PaymentDto toDto(Payment p);

    @Mapping(source = "invoice.id",            target = "invoiceId")
    @Mapping(source = "invoice.invoiceNumber", target = "invoiceNumber")
    PaymentDto.AllocationDto toAllocationDto(PaymentAllocation a);
}
