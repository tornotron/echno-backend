package org.tornotron.echno_backend.leave;

import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * CRUD and lifecycle for leave policies within an organization.
 *
 * <p>Enforces one policy per leave-type code per organization, supports partial updates and
 * activation toggles (policies are deactivated rather than deleted), and can copy a policy into
 * another organization the caller owns. Eligibility queries filter policies by an employee's
 * gender and accrued service months.
 */
@Service
@Validated
@Slf4j
public class LeavePolicyService {

    private final LeavePolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final UserContextService userContextService;
    private final LeavePolicyMapper leavePolicyMapper;

    public LeavePolicyService(
            LeavePolicyRepository policyRepository,
            OrganizationRepository organizationRepository,
            EmployeeRepository employeeRepository, UserContextService userContextService,
            LeavePolicyMapper leavePolicyMapper) {
        this.policyRepository = policyRepository;
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.userContextService = userContextService;
        this.leavePolicyMapper = leavePolicyMapper;
    }

    /**
     * Creates a leave policy for the current organization.
     *
     * <p>The leave-type code is stored uppercased and must be unique within the organization; the
     * policy is created active.
     *
     * @param dto The policy attributes.
     * @return The created policy.
     * @throws ResourceNotFoundException if the current organization is not found for this user.
     * @throws DuplicateResourceException if a policy with the same leave-type code already exists.
     */
    @Transactional
    public LeavePolicyDto createPolicy(LeavePolicyCreationDto dto) {
        Long organizationId = TenantContext.getCurrentOrgId();
        Organization organization = organizationRepository.findByIdAndUserEmail(organizationId, userContextService.getCurrentUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization with ID " + organizationId + " was not found"));

        // Both arguments are the values the row will actually carry, and neither used to be.
        //
        // The organization was taken from dto.organizationId, which is a caller-supplied field
        // that decides nothing else here: the policy is written into the tenant regardless. Since
        // LeavePolicy carries the orgFilter, any other value made the predicate unsatisfiable, so
        // the rule was skipped rather than applied. The code was taken as it arrived while the
        // row stores it uppercased, so a lower-case code was checked against a value the table
        // never holds. Either way uk_leave_policy_org_type still refused the insert, and the
        // caller was told only that some constraint failed instead of which code clashed.
        //
        // dto.organizationId stays in the payload: clients send it today and removing it is a
        // contract change, not a repair. See issue #700.
        // Locale.ROOT, because the casing the row is keyed by has to be a property of the
        // value rather than of the JVM that received it. The default locale would make it
        // the latter: under tr-TR a lower-case i uppercases to a dotted capital I, so the
        // same code entered by the same person is stored under two different keys in two
        // environments, and the uniqueness constraint is where that would surface. See #719.
        String leaveTypeCode = dto.getLeaveTypeCode().toUpperCase(Locale.ROOT);
        if (policyRepository.existsByOrganizationIdAndLeaveTypeCode(organizationId, leaveTypeCode)) {
            throw new DuplicateResourceException(
                    "Leave policy with code '" + leaveTypeCode +
                    "' already exists for this organization");
        }

        LeavePolicy policy = new LeavePolicy();
        policy.setOrganization(organization);
        policy.setLeaveTypeCode(leaveTypeCode);
        policy.setLeaveTypeName(dto.getLeaveTypeName());
        policy.setDescription(dto.getDescription());
        policy.setAnnualQuota(dto.getAnnualQuota());
        policy.setAccrualRatePerMonth(dto.getAccrualRatePerMonth());
        policy.setCarryForwardLimit(dto.getCarryForwardLimit());
        policy.setCarryForwardExpiryMonths(dto.getCarryForwardExpiryMonths());
        policy.setMaxDaysPerRequest(dto.getMaxDaysPerRequest());
        policy.setAttachmentRequiredAfterDays(dto.getAttachmentRequiredAfterDays());
        policy.setIsActive(true);

        // Only the fields the entity leaves to the caller are set unconditionally above. The nine
        // below carry a declared default, and setting those from an absent payload value is what
        // this repair is: the setter ran whatever arrived, so a request that omitted the field
        // overwrote the initialiser with null and Hibernate wrote NULL into a column whose default
        // then never applied either. multiLevelApprovalEnabled already had this guard, spelled out
        // as an if; the other eight did not.
        //
        // Nothing complained, because LeaveRequestValidator is null-safe by construction. A null
        // allowHalfDay makes Boolean.TRUE.equals(...) false, so a policy created to allow half days
        // refuses them; null minDaysPerRequest and null advanceNoticeDays make the != null guards
        // skip their checks outright, so the limits the policy was created with are not enforced.
        // The policy reads as configured in the UI and behaves as if it were not. See issue #741.
        applyIfPresent(dto.getMinDaysPerRequest(), policy::setMinDaysPerRequest);
        applyIfPresent(dto.getAdvanceNoticeDays(), policy::setAdvanceNoticeDays);
        applyIfPresent(dto.getRequiresAttachment(), policy::setRequiresAttachment);
        applyIfPresent(dto.getApplicableGenders(), policy::setApplicableGenders);
        applyIfPresent(dto.getMinServiceMonths(), policy::setMinServiceMonths);
        applyIfPresent(dto.getAllowHalfDay(), policy::setAllowHalfDay);
        applyIfPresent(dto.getIsPaid(), policy::setIsPaid);
        applyIfPresent(dto.getDisplayOrder(), policy::setDisplayOrder);
        applyIfPresent(dto.getMultiLevelApprovalEnabled(), policy::setMultiLevelApprovalEnabled);

        LeavePolicy saved = policyRepository.save(policy);
        return leavePolicyMapper.toDto(saved);
    }

    /**
     * Retrieves a single leave policy by its ID.
     *
     * @param policyId The ID of the policy to retrieve.
     * @return The policy.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional(readOnly = true)
    public LeavePolicyDto getPolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));
        return leavePolicyMapper.toDto(policy);
    }

    /**
     * Lists all leave policies visible to the current tenant.
     *
     * <p>Deliberately unpaginated. A leave policy is a leave type the organization defines
     * (annual, sick, casual and so on), so the count follows how the organization chooses to
     * structure its leave and not its headcount or how long it has been running. The rows that
     * grow with either of those are leave requests and balances, which are separate tables.
     *
     * @return Every policy for the current organization.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPolicies() {
        return policyRepository.findAll().stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the active policies for an organization, ordered by display order.
     *
     * <p>The organization has to be the caller's own, which the handler establishes with
     * {@code @orgSecurity.isCurrentTenant}. That is worth saying here because the
     * {@code existsById} below reads as though it were the tenant check and is not: it asks only
     * whether the organization exists anywhere in the deployment, and {@code Organization} is the
     * tenant root, so it carries neither the {@code orgFilter} nor the load listener's protection.
     * What keeps the result inside the tenant is the guard, plus the filter on
     * {@link LeavePolicy} itself.
     *
     * @param organizationId The organization's ID, which the guard has established is the caller's.
     * @return The active policies in display order.
     * @throws ResourceNotFoundException if the organization is not found.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization with ID " + organizationId + " was not found");
        }

        return policyRepository.findByOrganizationIdAndIsActiveTrueOrderByDisplayOrderAsc(organizationId)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists all policies for an organization, including inactive ones.
     *
     * @param organizationId The organization's ID.
     * @return Every policy for the organization.
     * @throws ResourceNotFoundException if the organization is not found.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization with ID " + organizationId + " was not found");
        }

        return policyRepository.findByOrganizationId(organizationId)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the policies an employee is currently eligible for.
     *
     * <p>Filters by the employee's gender and their service months computed from the joining date.
     *
     * @param employeeId The employee's ID.
     * @return The policies applicable to the employee.
     * @throws ResourceNotFoundException if the employee is not found in this organization.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getApplicablePoliciesForEmployee(Long employeeId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LocalDateTime joiningDate = employee.getJoiningDate();
        int serviceMonths = 0;
        if (joiningDate != null) {
            serviceMonths = (int) ChronoUnit.MONTHS.between(joiningDate, LocalDateTime.now());
        }

        return policyRepository.findApplicablePolicies(
                        employee.getOrganization().getId(),
                        employee.getGender(),
                        serviceMonths)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Applies a partial update to a policy.
     *
     * <p>Only the supplied keys are changed; the leave-type code and organization are not editable
     * here, and unrecognized keys are ignored.
     *
     * @param policyId The ID of the policy to update.
     * @param updates A map of field names to new values.
     * @return The updated policy.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public LeavePolicyDto updatePolicy(Long policyId, Map<String, Object> updates) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        updates.forEach((key, value) -> {
            switch (key) {
                case "leaveTypeName" -> policy.setLeaveTypeName((String) value);
                case "description" -> policy.setDescription((String) value);
                case "annualQuota" -> policy.setAnnualQuota(requireAnnualQuota(value));
                case "accrualRatePerMonth" -> policy.setAccrualRatePerMonth(
                        value != null ? ((Number) value).doubleValue() : null);
                case "carryForwardLimit" -> policy.setCarryForwardLimit(
                        value != null ? ((Number) value).doubleValue() : null);
                case "carryForwardExpiryMonths" -> policy.setCarryForwardExpiryMonths(
                        value != null ? ((Number) value).intValue() : null);
                case "minDaysPerRequest" -> policy.setMinDaysPerRequest(
                        value != null ? ((Number) value).doubleValue() : null);
                case "maxDaysPerRequest" -> policy.setMaxDaysPerRequest(
                        value != null ? ((Number) value).doubleValue() : null);
                case "advanceNoticeDays" -> policy.setAdvanceNoticeDays(
                        value != null ? ((Number) value).intValue() : null);
                case "requiresAttachment" -> policy.setRequiresAttachment((Boolean) value);
                case "attachmentRequiredAfterDays" -> policy.setAttachmentRequiredAfterDays(
                        value != null ? ((Number) value).intValue() : null);
                case "applicableGenders" -> policy.setApplicableGenders((String) value);
                case "minServiceMonths" -> policy.setMinServiceMonths(
                        value != null ? ((Number) value).intValue() : null);
                case "allowHalfDay" -> policy.setAllowHalfDay((Boolean) value);
                case "isPaid" -> policy.setIsPaid((Boolean) value);
                case "isActive" -> policy.setIsActive((Boolean) value);
                case "multiLevelApprovalEnabled" -> policy.setMultiLevelApprovalEnabled((Boolean) value);
                case "displayOrder" -> policy.setDisplayOrder(
                        value != null ? ((Number) value).intValue() : null);
                // Nothing is dropped on purpose here: echno-core sends this endpoint no key it
                // does not declare. See echno-core#57.
                default -> PartialUpdateKeys.reportUnknown(log, "leave policy", policyId, key);
            }
        });

        LeavePolicy saved = policyRepository.save(policy);
        return leavePolicyMapper.toDto(saved);
    }

    /**
     * Reads the {@code annualQuota} key of a partial leave-policy update.
     *
     * <p>Null is refused rather than applied. {@code annual_quota} is a {@code NOT NULL} column, so
     * clearing it cannot be written; before this refusal existed the branch reached
     * {@code ((Number) null).doubleValue()} and answered 500. Every other numeric key on this
     * switch guards null and clears, because every other numeric column is nullable. See #645.
     *
     * @param value The raw map value.
     * @return The quota.
     * @throws InvalidRequestException if the value is null.
     */
    private Double requireAnnualQuota(Object value) {
        if (value == null) {
            throw new InvalidRequestException(
                    "A leave policy must have an annual quota; annualQuota cannot be cleared");
        }
        return ((Number) value).doubleValue();
    }


    /**
     * Marks a policy inactive, keeping it for historical reference.
     *
     * @param policyId The ID of the policy to deactivate.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public void deactivatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        policy.setIsActive(false);
        policyRepository.save(policy);
    }

    /**
     * Marks a previously deactivated policy active again.
     *
     * @param policyId The ID of the policy to activate.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public void activatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        policy.setIsActive(true);
        policyRepository.save(policy);
    }

    /**
     * Copies a policy's attributes into another organization the caller owns.
     *
     * <p>The copy is created active and keeps the source's leave-type code, which must not already
     * exist in the target organization.
     *
     * @param policyId The ID of the source policy.
     * @param targetOrganizationId The ID of the organization to copy into.
     * @return The newly created policy in the target organization.
     * @throws ResourceNotFoundException if the source policy or target organization is not found.
     * @throws DuplicateResourceException if the target already has a policy with the same leave-type code.
     */
    @Transactional
    public LeavePolicyDto duplicatePolicy(Long policyId, Long targetOrganizationId) {
        LeavePolicy source = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        Organization targetOrg = organizationRepository.findByIdAndUserEmail(targetOrganizationId,userContextService.getCurrentUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target organization with ID " + targetOrganizationId + " was not found"));

        // Asked without the filter, because the filter is what made the old form unanswerable.
        //
        // This used to call existsByOrganizationIdAndLeaveTypeCode, which runs against LeavePolicy
        // as a query root and therefore carries orgFilter. For any target other than the current
        // tenant the filter's predicate and the argument named two different organizations, so the
        // query matched nothing whatever the table held and the uniqueness rule was skipped on the
        // one path that needs it. A real collision then reached uk_leave_policy_org_type and came
        // back as a 500 rather than the 409 written here.
        //
        // Naming the current tenant instead is not the repair, though it looks like the smaller
        // one: the check would then match the source policy's own code every time and the endpoint
        // would answer 409 to every input. The question genuinely is about another organization,
        // so it has to be asked in a query the filter does not narrow. See #718.
        if (policyRepository.countWithLeaveTypeCodeInOrganizationUnfiltered(
                targetOrganizationId, source.getLeaveTypeCode()) > 0) {
            throw new DuplicateResourceException(
                    "Leave policy with code '" + source.getLeaveTypeCode() +
                    "' already exists in target organization");
        }

        LeavePolicy duplicate = new LeavePolicy();
        duplicate.setOrganization(targetOrg);
        duplicate.setLeaveTypeCode(source.getLeaveTypeCode());
        duplicate.setLeaveTypeName(source.getLeaveTypeName());
        duplicate.setDescription(source.getDescription());
        duplicate.setAnnualQuota(source.getAnnualQuota());
        duplicate.setAccrualRatePerMonth(source.getAccrualRatePerMonth());
        duplicate.setCarryForwardLimit(source.getCarryForwardLimit());
        duplicate.setCarryForwardExpiryMonths(source.getCarryForwardExpiryMonths());
        duplicate.setMaxDaysPerRequest(source.getMaxDaysPerRequest());
        duplicate.setAttachmentRequiredAfterDays(source.getAttachmentRequiredAfterDays());
        duplicate.setIsActive(true);

        // The same guard, for a different reason. A policy written before the repair above can
        // hold null in a field that has a declared default, and copying that null forward spreads
        // a state the policy was never configured into: the copy would refuse half days and skip
        // the limit checks exactly as the original does. Falling back to the initialiser makes the
        // duplicate differ from its source only where the source is already broken, which is the
        // direction worth choosing.
        applyIfPresent(source.getMinDaysPerRequest(), duplicate::setMinDaysPerRequest);
        applyIfPresent(source.getAdvanceNoticeDays(), duplicate::setAdvanceNoticeDays);
        applyIfPresent(source.getRequiresAttachment(), duplicate::setRequiresAttachment);
        applyIfPresent(source.getApplicableGenders(), duplicate::setApplicableGenders);
        applyIfPresent(source.getMinServiceMonths(), duplicate::setMinServiceMonths);
        applyIfPresent(source.getAllowHalfDay(), duplicate::setAllowHalfDay);
        applyIfPresent(source.getIsPaid(), duplicate::setIsPaid);
        applyIfPresent(source.getDisplayOrder(), duplicate::setDisplayOrder);
        applyIfPresent(source.getMultiLevelApprovalEnabled(), duplicate::setMultiLevelApprovalEnabled);

        LeavePolicy saved = policyRepository.save(duplicate);
        return leavePolicyMapper.toDto(saved);
    }

    /**
     * Runs the setter only when a value was supplied, leaving the entity's own initialiser in
     * place when it was not.
     *
     * <p>A plain setter cannot express the difference between "the caller chose null" and "the
     * caller said nothing", and for a field with a declared default the two mean opposite things.
     * Every column this is used on is nullable in the schema, so nothing rejects the null and the
     * mistake surfaces later as behaviour instead of as an error.
     *
     * @param value The supplied value, or null when the field was not supplied.
     * @param setter The setter to run when the value is present.
     * @param <T> The field's type.
     */
    private static <T> void applyIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
