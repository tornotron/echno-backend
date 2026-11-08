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
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.organization.Organization;

import java.util.Locale;


/**
 * CRUD + list for expenses. The expense is a flat header scoped to the current tenant;
 * its number is generated on create and never changes afterwards.
 *
 * <p>Each read and write asks the access matrix about the expense itself ({@link ExpenseAccess}):
 * the list is narrowed to what the caller may read, one they may not read is reported missing, and
 * a write is checked on the expense as it stands and on where it leaves it.
 */
@Service
public class ExpenseService {

    private static final String DOC_TYPE = "EXP";

    private final ExpenseRepository expenseRepository;
    private final ExpenseMapper expenseMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EntryNumberGenerator numberGenerator;
    private final ExpenseAccess expenseAccess;

    public ExpenseService(ExpenseRepository expenseRepository,
                          ExpenseMapper expenseMapper,
                          TenantEntityHelper tenantEntityHelper,
                          EntryNumberGenerator numberGenerator,
                          ExpenseAccess expenseAccess) {
        this.expenseRepository = expenseRepository;
        this.expenseMapper = expenseMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.numberGenerator = numberGenerator;
        this.expenseAccess = expenseAccess;
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
        // Checked on the expense as recorded, so nobody records one where they may not.
        expenseAccess.require(AccessPermission.CREATE, ExpenseAccess.facts(expense));

        // saveAndFlush before mapping so the @CreationTimestamp and generated id are
        // populated on the returned DTO.
        Expense saved = expenseRepository.saveAndFlush(expense);
        return expenseMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public ExpenseDto getById(Long id) {
        return expenseMapper.toDto(requireExpense(id, AccessPermission.READ));
    }


    @Transactional(readOnly = true)
    public Page<ExpenseDto> getPaginated(int pageNo, int pageSize, String search, String status) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        AccessFilter visible = expenseAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        String pattern = searchPattern(search);
        String wanted = blankToNull(status);
        Page<Expense> expenses = visible.everything()
                ? expenseRepository.search(pattern, wanted, pageable)
                : expenseRepository.findAll(
                        ExpenseAccess.admittedBy(visible).and(ExpenseRepository.matching(pattern, wanted)), pageable);
        return expenses.map(expenseMapper::toDto);
    }

    @Transactional
    public ExpenseDto update(Long id, ExpenseUpdateDto updateDto) {
        Expense expense = requireExpense(id, AccessPermission.EDIT);

        applyFields(expense, updateDto.getType(), updateDto.getCategory(), updateDto.getStatus(),
                updateDto.getDescription(), updateDto.getAmount(), updateDto.getCurrency(),
                updateDto.getExpenseDate(), updateDto.getPaymentMethod(), updateDto.getNotes(),
                updateDto.getProjectId(), updateDto.getVendorId(), updateDto.getEmployeeId(),
                updateDto.getInvoiceId(), updateDto.getPaymentId(), updateDto.getBudgetId());
        // An edit can move the expense to another project, and must not move it out of what the
        // caller may edit.
        expenseAccess.require(AccessPermission.EDIT, ExpenseAccess.facts(expense));

        // saveAndFlush before mapping so the @UpdateTimestamp on the returned DTO reflects
        // this write.
        Expense saved = expenseRepository.saveAndFlush(expense);
        return expenseMapper.toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        expenseRepository.delete(requireExpense(id, AccessPermission.DELETE));
    }

    /**
     * The expense, if the caller may act on it this way. One they may not read is reported missing,
     * as one in another organization is; one they may read but not act on this way is refused.
     */
    private Expense requireExpense(Long id, String action) {
        Expense expense = expenseRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        expenseAccess.check(action, ExpenseAccess.facts(expense), () -> notFound(id));
        return expense;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Expense with ID " + id + " was not found in this organization");
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
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
