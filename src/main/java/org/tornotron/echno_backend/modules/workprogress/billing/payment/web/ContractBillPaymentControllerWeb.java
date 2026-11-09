package org.tornotron.echno_backend.modules.workprogress.billing.payment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.workprogress.WorkProgressModule;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillCommentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentService;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentsDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.ProjectPaymentAuthorizerDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RaiseBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RecordBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.SetPaymentAuthorizerRequest;

/** The web surface of bill payments, the twin of {@link ContractBillPaymentController}. */
@RestController
@RequestMapping("/api/v1/contract-billing/web")
@RequireSubscription(feature = WorkProgressModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Contract Billing", description = "Work Progress module: running account and milestone bills from the claim to final approval, with the contract BOQ, deduction rules and milestone requirements, and paying an approved bill. Reads for members; the guards per step are in docs/specs/2026-10-04-ra-milestone-billing.md and docs/specs/2026-11-08-billing-in-inspection-and-payments.md.")
public class ContractBillPaymentControllerWeb {

    private final BillPaymentService payments;

    @GetMapping("/bills/{id}/payments")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "A bill's payments and what is left to pay")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payments, newest first, with the payable's figures"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public BillPaymentsDto list(@PathVariable UUID id) {
        return payments.list(id);
    }

    @GetMapping("/projects/{projectId}/payment-authorizer")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "Who authorizes payments on a project's bills", description = "The employee the project names, whether the reader is that person or may change it, and the latest changes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The project's payment authorizer, or none"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public ProjectPaymentAuthorizerDto authorizer(@PathVariable Long projectId) {
        return payments.authorizer(projectId);
    }

    @PutMapping("/projects/{projectId}/payment-authorizer")
    @PreAuthorize("@access.can('progress-bills', 'edit')")
    @Operation(summary = "Name a project's payment authorizer", description = "Replaces the one before; the change is kept with who made it. Holding the role to do this does not let the caller authorize payments.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Named"),
            @ApiResponse(responseCode = "400", description = "The employee is no longer active"),
            @ApiResponse(responseCode = "403", description = "Caller may not make this change to contractor bills"),
            @ApiResponse(responseCode = "404", description = "No such project or employee in the current tenant")
    })
    public ProjectPaymentAuthorizerDto setAuthorizer(@PathVariable Long projectId,
                                                     @Valid @RequestBody SetPaymentAuthorizerRequest req) {
        return payments.setAuthorizer(projectId, req);
    }

    @DeleteMapping("/projects/{projectId}/payment-authorizer")
    @PreAuthorize("@access.can('progress-bills', 'edit')")
    @Operation(summary = "Leave a project with no payment authorizer", description = "Its payments wait until one is named. The change is kept.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cleared"),
            @ApiResponse(responseCode = "403", description = "Caller may not make this change to contractor bills"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public ProjectPaymentAuthorizerDto clearAuthorizer(@PathVariable Long projectId) {
        return payments.clearAuthorizer(projectId);
    }

    @PostMapping("/bills/{id}/payments")
    @PreAuthorize("@access.can('progress-bills', 'approve')")
    @Operation(summary = "Raise a payment on an approved bill", description = "An amount up to what is due; less is a part payment. It then waits for the second signature. A bill has one payment in flight at a time.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Raised"),
            @ApiResponse(responseCode = "400", description = "Not approved, nothing due, more than is due, one already in flight, or no bank details for a provider to pay"),
            @ApiResponse(responseCode = "403", description = "Caller may not make this change to contractor bills"),
            @ApiResponse(responseCode = "404", description = "No such bill in the current tenant")
    })
    public ResponseEntity<BillPaymentsDto> raise(@PathVariable UUID id, @Valid @RequestBody RaiseBillPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payments.raise(id, req));
    }

    @PostMapping("/bill-payments/{paymentId}/authorize")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "Authorize a payment", description = "The second signature, which only the project's payment authorizer gives, and never on a payment they raised. Sends it when a provider is configured. A send with no answer stays authorized and shows the error; sync tries again safely.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authorized, and sent if a provider carries it"),
            @ApiResponse(responseCode = "400", description = "The caller raised it, it is not waiting for a signature, or it is now more than is due"),
            @ApiResponse(responseCode = "403", description = "Caller is not the project's payment authorizer, or the project has none"),
            @ApiResponse(responseCode = "404", description = "No such payment in the current tenant")
    })
    public BillPaymentsDto authorize(@PathVariable UUID paymentId) {
        return payments.authorize(paymentId);
    }

    @PostMapping("/bill-payments/{paymentId}/reject")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "Reject a payment", description = "With a reason, before a provider has acknowledged it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rejected"),
            @ApiResponse(responseCode = "400", description = "It can no longer be rejected"),
            @ApiResponse(responseCode = "403", description = "Caller is not the project's payment authorizer, or the project has none"),
            @ApiResponse(responseCode = "404", description = "No such payment in the current tenant")
    })
    public BillPaymentsDto reject(@PathVariable UUID paymentId, @Valid @RequestBody BillCommentRequest req) {
        return payments.reject(paymentId, req.text());
    }

    @PostMapping("/bill-payments/{paymentId}/record")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "Record that a manual payment was made", description = "With the bank reference and date. This settles it and records it on the payable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settled"),
            @ApiResponse(responseCode = "400", description = "It is not an authorized manual payment, or the date is in the future"),
            @ApiResponse(responseCode = "403", description = "Caller is not the project's payment authorizer, or the project has none"),
            @ApiResponse(responseCode = "404", description = "No such payment in the current tenant")
    })
    public BillPaymentsDto record(@PathVariable UUID paymentId, @Valid @RequestBody RecordBillPaymentRequest req) {
        return payments.record(paymentId, req);
    }

    @PostMapping("/bill-payments/{paymentId}/sync")
    @PreAuthorize("@access.can('progress-bills', 'read')")
    @Operation(summary = "Send again or ask the provider", description = "Sends again a payment whose send got no answer, with the same idempotency key, or asks where one stands.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What is now known"),
            @ApiResponse(responseCode = "400", description = "It is manual, so a person finishes it"),
            @ApiResponse(responseCode = "403", description = "Caller is not the project's payment authorizer, or the project has none"),
            @ApiResponse(responseCode = "404", description = "No such payment in the current tenant")
    })
    public BillPaymentsDto sync(@PathVariable UUID paymentId) {
        return payments.sync(paymentId);
    }
}
