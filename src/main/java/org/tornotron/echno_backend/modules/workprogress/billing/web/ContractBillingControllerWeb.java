package org.tornotron.echno_backend.modules.workprogress.billing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.modules.workprogress.WorkProgressModule;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillCommentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillEventDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillSummaryDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillingOverviewDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ContractBillingDetailDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ContractBillingSummaryDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.CreateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.DeductionRuleDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.DeductionRuleRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ManualAdjustmentsRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.UpdateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillPdfService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;

/**
 * The web surface of contract billing, the twin of {@link ContractBillingController} under {@code /web}, where the
 * project office prepares, measures, certifies and approves bills. The two expose the
 * same operations under the same guards.
 */
@RestController
@RequestMapping("/api/v1/contract-billing/web")
@RequireSubscription(feature = WorkProgressModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Contract Billing (web)", description = "Web twin of the contract billing surface. Work Progress module: running account and milestone bills from the claim to final approval, with the contract BOQ, deduction rules and milestone requirements. Reads for members; the guards per step are in docs/specs/2026-10-04-ra-milestone-billing.md.")
public class ContractBillingControllerWeb {

    private final ContractBillingService billing;
    private final ContractBillService bills;
    private final ContractBillPdfService pdf;

    // ---------------------------------------------------------------- home page and contracts

    @GetMapping("/overview")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "Organization-wide billing figures for the home page")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Counts by stage and the certified and approved totals"))
    public BillingOverviewDto overview() {
        return billing.overview();
    }

    @GetMapping("/contracts")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List contracts with their billing state, newest first", description = "Filter by project.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of contracts"))
    public Page<ContractBillingSummaryDto> contracts(@RequestParam(required = false) Long projectId, @Valid PageQuery page) {
        return billing.contracts(projectId, page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/contracts/{subContractId}")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "Get one contract's billing: BOQ, deduction rules, milestones and bills")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The contract's billing"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public ContractBillingDetailDto contract(@PathVariable Long subContractId) {
        return billing.contract(subContractId);
    }

    // ---------------------------------------------------------------- BOQ

    @GetMapping("/contracts/{subContractId}/boq-items")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List a contract's BOQ items")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The BOQ in order"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public List<BoqItemDto> listBoq(@PathVariable Long subContractId) {
        return billing.listBoq(subContractId);
    }

    @PostMapping("/contracts/{subContractId}/boq-items")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Add a BOQ item to a contract")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "BOQ item added"),
            @ApiResponse(responseCode = "400", description = "The code is already on the BOQ, or the activity is not in the contract's project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such subcontract or activity in the current tenant")
    })
    public ResponseEntity<BoqItemDto> addBoqItem(@PathVariable Long subContractId, @Valid @RequestBody BoqItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billing.addBoqItem(subContractId, req));
    }

    @PutMapping("/boq-items/{itemId}")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Change a BOQ item", description = "Bills already opened keep the code, quantity and rate they copied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "BOQ item changed"),
            @ApiResponse(responseCode = "400", description = "The code is taken, or the quantity is below what is certified"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such BOQ item in the current tenant")
    })
    public BoqItemDto updateBoqItem(@PathVariable UUID itemId, @Valid @RequestBody BoqItemRequest req) {
        return billing.updateBoqItem(itemId, req);
    }

    @DeleteMapping("/boq-items/{itemId}")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Delete a BOQ item no bill uses")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "BOQ item deleted"),
            @ApiResponse(responseCode = "400", description = "A bill uses the item"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such BOQ item in the current tenant")
    })
    public ResponseEntity<Void> deleteBoqItem(@PathVariable UUID itemId) {
        billing.deleteBoqItem(itemId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- deduction rules

    @GetMapping("/contracts/{subContractId}/deduction-rules")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List a contract's deduction rules")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The rules in order"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public List<DeductionRuleDto> listRules(@PathVariable Long subContractId) {
        return billing.listRules(subContractId);
    }

    @PostMapping("/contracts/{subContractId}/deduction-rules")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Add a deduction rule to a contract")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Rule added"),
            @ApiResponse(responseCode = "400", description = "A percent rule has no rate, or a fixed rule no amount"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public ResponseEntity<DeductionRuleDto> addRule(@PathVariable Long subContractId, @Valid @RequestBody DeductionRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billing.addRule(subContractId, req));
    }

    @PutMapping("/deduction-rules/{ruleId}")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Change a deduction rule", description = "Certified bills keep the amounts they froze.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule changed"),
            @ApiResponse(responseCode = "400", description = "A percent rule has no rate, or a fixed rule no amount"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such rule in the current tenant")
    })
    public DeductionRuleDto updateRule(@PathVariable UUID ruleId, @Valid @RequestBody DeductionRuleRequest req) {
        return billing.updateRule(ruleId, req);
    }

    @DeleteMapping("/deduction-rules/{ruleId}")
    @PreAuthorize(WorkProgressModule.BILLING_SETUP_GUARD)
    @Operation(summary = "Delete a deduction rule no certified bill has applied")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rule deleted"),
            @ApiResponse(responseCode = "400", description = "A certified bill has applied it; disable it instead"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such rule in the current tenant")
    })
    public ResponseEntity<Void> deleteRule(@PathVariable UUID ruleId) {
        billing.deleteRule(ruleId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- milestone requirements

    @GetMapping("/contracts/{subContractId}/milestones/{milestoneId}/requirements")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List a contract milestone's requirements")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requirements in order"),
            @ApiResponse(responseCode = "400", description = "The milestone is not part of the contract"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public List<MilestoneRequirementDto> listRequirements(@PathVariable Long subContractId, @PathVariable Long milestoneId) {
        return billing.listRequirements(subContractId, milestoneId);
    }

    @PostMapping("/contracts/{subContractId}/milestones/{milestoneId}/requirements")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Add a requirement to a contract milestone")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Requirement added"),
            @ApiResponse(responseCode = "400", description = "The milestone is not part of the contract"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public ResponseEntity<MilestoneRequirementDto> addRequirement(@PathVariable Long subContractId,
                                                                  @PathVariable Long milestoneId,
                                                                  @Valid @RequestBody MilestoneRequirementRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billing.addRequirement(subContractId, milestoneId, req));
    }

    @PutMapping("/requirements/{requirementId}")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Change a milestone requirement, including its status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Requirement changed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such requirement in the current tenant")
    })
    public MilestoneRequirementDto updateRequirement(@PathVariable UUID requirementId,
                                                     @Valid @RequestBody MilestoneRequirementRequest req) {
        return billing.updateRequirement(requirementId, req);
    }

    @DeleteMapping("/requirements/{requirementId}")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Delete a milestone requirement")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Requirement deleted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such requirement in the current tenant")
    })
    public ResponseEntity<Void> deleteRequirement(@PathVariable UUID requirementId) {
        billing.deleteRequirement(requirementId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- bills

    @GetMapping("/bills")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List bills, newest first", description = "Filter by project, contract, status and billing model; all optional.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of bills"))
    public Page<BillSummaryDto> listBills(@RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) Long subContractId,
                                          @RequestParam(required = false) BillStatus status,
                                          @RequestParam(required = false) BillingModel billingModel,
                                          @Valid PageQuery page) {
        return bills.list(projectId, subContractId, status, billingModel, page.getPageNo(), page.getPageSize());
    }

    @PostMapping("/bills")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Open a running account or milestone bill on a contract",
            description = "The first bill fixes the contract's billing model, and a contract has one open bill at a time. "
                    + "A running account bill lists every BOQ item with its previous certified quantity.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bill opened as a draft"),
            @ApiResponse(responseCode = "400", description = "Another bill is open, the model differs from the contract's, the period overlaps, the BOQ is empty, or the milestone has no value"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such subcontract in the current tenant")
    })
    public ResponseEntity<BillDto> createBill(@Valid @RequestBody CreateBillRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bills.create(req));
    }

    @GetMapping("/bills/{id}")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "Get one bill with its lines, adjustments and running account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The bill"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto getBill(@PathVariable UUID id) {
        return bills.get(id);
    }

    @PutMapping("/bills/{id}")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Change the claim of a draft or returned bill")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claim saved"),
            @ApiResponse(responseCode = "400", description = "The bill is past the claim, a claim passes the contract quantity or the milestone, or the period overlaps"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto updateBill(@PathVariable UUID id, @Valid @RequestBody UpdateBillRequest req) {
        return bills.update(id, req);
    }

    @PostMapping("/bills/{id}/submit")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Submit a bill for joint measurement")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill submitted"),
            @ApiResponse(responseCode = "400", description = "The bill is not a draft or returned, or claims nothing"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto submitBill(@PathVariable UUID id) {
        return bills.submit(id);
    }

    @PostMapping("/bills/{id}/cancel")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Cancel a draft or returned bill")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill cancelled"),
            @ApiResponse(responseCode = "400", description = "The bill is not a draft or returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto cancelBill(@PathVariable UUID id) {
        return bills.cancel(id);
    }

    @PutMapping("/bills/{id}/measurement")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Record the joint measurement of a submitted bill", description = "May be saved more than once before the bill is verified.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Measurement saved"),
            @ApiResponse(responseCode = "400", description = "The bill is not submitted, a date is in the future, or more is accepted than claimed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto saveMeasurement(@PathVariable UUID id, @Valid @RequestBody MeasurementRequest req) {
        return bills.saveMeasurement(id, req);
    }

    @PostMapping("/bills/{id}/verify")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Verify a measured bill")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill verified"),
            @ApiResponse(responseCode = "400", description = "The bill is not submitted, has no measurement date, a claimed line has no accepted quantity, or nothing was accepted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto verifyBill(@PathVariable UUID id) {
        return bills.verify(id);
    }

    @PostMapping("/bills/{id}/return")
    @PreAuthorize(WorkProgressModule.BILL_VERIFY_GUARD)
    @Operation(summary = "Return a bill to the preparer for correction", description = "A certified bill comes back with its figures unfrozen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill returned"),
            @ApiResponse(responseCode = "400", description = "The bill is not submitted, verified or certified"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto returnBill(@PathVariable UUID id, @Valid @RequestBody BillCommentRequest req) {
        return bills.returnForCorrection(id, req);
    }

    @PutMapping("/bills/{id}/adjustments")
    @PreAuthorize(WorkProgressModule.BILL_CERTIFY_GUARD)
    @Operation(summary = "Replace the manual adjustments of a bill not yet certified")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Adjustments saved"),
            @ApiResponse(responseCode = "400", description = "The bill is certified, approved or cancelled"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto replaceAdjustments(@PathVariable UUID id, @Valid @RequestBody ManualAdjustmentsRequest req) {
        return bills.replaceManualAdjustments(id, req);
    }

    @PostMapping("/bills/{id}/certify")
    @PreAuthorize(WorkProgressModule.BILL_CERTIFY_GUARD)
    @Operation(summary = "Certify a verified bill", description = "Applies the contract's deduction rules and freezes every figure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill certified"),
            @ApiResponse(responseCode = "400", description = "The bill is not verified, a mandatory milestone requirement is open, or the net would be negative"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto certifyBill(@PathVariable UUID id) {
        return bills.certify(id);
    }

    @PostMapping("/bills/{id}/approve")
    @PreAuthorize(WorkProgressModule.BILL_APPROVE_GUARD)
    @Operation(summary = "Give a certified bill final approval", description = "Hands the net payable to finance as a payable. "
            + "The approver must not be the certifier, unless a system admin, which is recorded.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bill approved"),
            @ApiResponse(responseCode = "400", description = "The bill is not certified, the approver certified it, or the approver has no employee record"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillDto approveBill(@PathVariable UUID id) {
        return bills.approve(id);
    }

    @GetMapping("/bills/{id}/events")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "A bill's timeline, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The timeline"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public List<BillEventDto> billEvents(@PathVariable UUID id) {
        return bills.events(id);
    }

    @PostMapping("/bills/{id}/notes")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Add a note to a bill's timeline")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Note added"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public ResponseEntity<BillEventDto> addNote(@PathVariable UUID id, @Valid @RequestBody BillCommentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bills.addNote(id, req));
    }

    @GetMapping("/bills/{id}/pdf")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "Download a bill as a PDF")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The PDF"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public ResponseEntity<byte[]> billPdf(@PathVariable UUID id) throws IOException {
        BillDto bill = bills.get(id);
        byte[] body = pdf.render(bill);
        String filename = (bill.contractRef() != null ? bill.contractRef() + "-" : "") + bill.billNumber();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    // ---------------------------------------------------------------- supporting documents

    @GetMapping("/bills/{id}/documents")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List a bill's supporting documents")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents returned"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public List<AttachmentDto> listDocuments(@PathVariable UUID id) {
        return bills.listDocuments(id);
    }

    @PostMapping("/bills/{id}/documents/presign")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Presign document uploads for a bill", description = "Step one of the direct-to-storage path.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "The bill is closed, or a file was declared twice or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public List<PresignedUpload> presignDocuments(@PathVariable UUID id, @RequestBody List<UploadRequest> uploads) {
        return bills.presignDocuments(id, uploads);
    }

    @PostMapping("/bills/{id}/documents/register")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Register presigned document uploads for a bill",
            description = "Step two: confirms the keys once each object is in storage, filed under the document type given "
                    + "(photo, test-report, delivery-challan, measurement or other; other when omitted).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Documents registered"),
            @ApiResponse(responseCode = "400", description = "The bill is closed, the type is unknown, or a key was not presigned for this bill"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerDocuments(@PathVariable UUID id,
                                                                 @RequestParam(required = false) String documentType,
                                                                 @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bills.registerDocuments(id, documentType, uploads));
    }

    @DeleteMapping("/bills/{id}/documents/{attachmentId}")
    @PreAuthorize(WorkProgressModule.BILL_PREPARE_GUARD)
    @Operation(summary = "Remove a supporting document from an open bill")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Document removed"),
            @ApiResponse(responseCode = "400", description = "The bill is closed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such bill, or the document is not filed against it")
    })
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id, @PathVariable Long attachmentId) {
        bills.deleteDocument(id, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
