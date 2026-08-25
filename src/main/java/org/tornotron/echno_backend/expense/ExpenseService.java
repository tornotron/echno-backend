package org.tornotron.echno_backend.expense;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.expense.dto.ExpenseCreationDto;
import org.tornotron.echno_backend.expense.dto.ExpenseDto;
import org.tornotron.echno_backend.expense.dto.ExpenseUpdateDto;
import org.tornotron.echno_backend.expense.mapper.ExpenseMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD + list for expenses. The expense is a flat header scoped to the current tenant;
 * its number is generated on create and never changes afterwards.
 */
@Service
public class ExpenseService {

    private static final String DOC_TYPE = "EXP";

    private final ExpenseRepository expenseRepository;
    private final ExpenseMapper expenseMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EntryNumberGenerator numberGenerator;

    public ExpenseService(ExpenseRepository expenseRepository,
                          ExpenseMapper expenseMapper,
                          TenantEntityHelper tenantEntityHelper,
                          EntryNumberGenerator numberGenerator) {
        this.expenseRepository = expenseRepository;
        this.expenseMapper = expenseMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.numberGenerator = numberGenerator;
    }

    @Transactional
    public ExpenseDto create(ExpenseCreationDto creationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        Expense expense = new Expense();
        expense.setOrganization(organization);
        expense.setExpenseNumber(numberGenerator.next(DOC_TYPE));
        applyFields(expense, creationDto.getType(), creationDto.getCategory(), creationDto.getStatus(),
                creationDto.getDescription(), creationDto.getAmount(), creationDto.getCurrency(),
                creationDto.getExpenseDate(), creationDto.getPaymentMethod(), creationDto.getNotes(),
                creationDto.getProjectId(), creationDto.getVendorId(), creationDto.getEmployeeId(),
                creationDto.getInvoiceId(), creationDto.getPaymentId(), creationDto.getBudgetId());

        // saveAndFlush before mapping so the @CreationTimestamp and generated id are
        // populated on the returned DTO.
        Expense saved = expenseRepository.saveAndFlush(expense);
        return expenseMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public ExpenseDto getById(Long id) {
        Expense expense = expenseRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense with ID " + id + " was not found in this organization"));
        return expenseMapper.toDto(expense);
    }

    @Transactional(readOnly = true)
    public List<ExpenseDto> getAll() {
        return expenseRepository.findAll().stream()
                .map(expenseMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ExpenseDto> getPaginated(int pageNo, int pageSize, String search, String status) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return expenseRepository.search(searchPattern(search), blankToNull(status), pageable)
                .map(expenseMapper::toDto);
    }

    @Transactional
    public ExpenseDto update(Long id, ExpenseUpdateDto updateDto) {
        Expense expense = expenseRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense with ID " + id + " was not found in this organization"));

        applyFields(expense, updateDto.getType(), updateDto.getCategory(), updateDto.getStatus(),
                updateDto.getDescription(), updateDto.getAmount(), updateDto.getCurrency(),
                updateDto.getExpenseDate(), updateDto.getPaymentMethod(), updateDto.getNotes(),
                updateDto.getProjectId(), updateDto.getVendorId(), updateDto.getEmployeeId(),
                updateDto.getInvoiceId(), updateDto.getPaymentId(), updateDto.getBudgetId());

        // saveAndFlush before mapping so the @UpdateTimestamp on the returned DTO reflects
        // this write.
        Expense saved = expenseRepository.saveAndFlush(expense);
        return expenseMapper.toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        Expense expense = expenseRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense with ID " + id + " was not found in this organization"));
        expenseRepository.delete(expense);
    }

    /**
     * Copies the editable scalars onto the expense. The expense number, organization and
     * timestamps are managed elsewhere and never set from a request. Currency defaults to
     * the entity's INR when the request omits it.
     */
    private void applyFields(Expense expense, String type, String category, String status,
                             String description, java.math.BigDecimal amount, String currency,
                             java.time.LocalDate expenseDate, String paymentMethod, String notes,
                             Long projectId, Long vendorId, Long employeeId,
                             Long invoiceId, Long paymentId, Long budgetId) {
        expense.setType(type);
        expense.setCategory(category);
        expense.setStatus(status);
        expense.setDescription(description);
        expense.setAmount(amount);
        if (currency != null) {
            expense.setCurrency(currency);
        }
        expense.setExpenseDate(expenseDate);
        expense.setPaymentMethod(paymentMethod);
        expense.setNotes(notes);
        expense.setProjectId(projectId);
        expense.setVendorId(vendorId);
        expense.setEmployeeId(employeeId);
        expense.setInvoiceId(invoiceId);
        expense.setPaymentId(paymentId);
        expense.setBudgetId(budgetId);
    }

    /**
     * Builds a lower-cased {@code %...%} LIKE pattern for the search term, or null when
     * blank. The pattern is assembled here rather than with SQL {@code CONCAT} so no null
     * bind lands inside a {@code ||}, which CockroachDB mistypes as bytes.
     */
    private static String searchPattern(String value) {
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase() + "%";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
