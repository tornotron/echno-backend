package org.tornotron.echno_backend.billing.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.webhook.BillingEventAdminService;
import org.tornotron.echno_backend.billing.webhook.BillingEventDto;

import java.util.List;

/**
 * The dead-letter view of the webhook inbox: which events the projector could not apply,
 * why, and a retry that hands one back to it.
 *
 * <p>The inbox is global, so this is a platform surface, guarded by the platform-admin role
 * like the feature catalogue and not by a tenant's own system-admin, who must not see or
 * replay another organization's events.
 */
@RestController
@RequestMapping("/api/v1/billing/web/events")
@RequiredArgsConstructor
@Tag(name = "Billing events", description = "Webhook inbox dead letters and retry")
public class BillingEventAdminController {

    private static final int MAX_PAGE_SIZE = 200;

    private final BillingEventAdminService service;

    @Operation(summary = "List webhook inbox rows",
            description = "Newest first. Without a status filter, shows FAILED and SKIPPED rows: the dead letters.")
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping
    public ResponseEntity<Page<BillingEventDto>> list(
            @Parameter(description = "Narrow to one organization") @RequestParam(required = false) Long organizationId,
            @Parameter(description = "Narrow to one provider event type, such as subscription.charged")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Statuses to show; defaults to FAILED and SKIPPED")
            @RequestParam(required = false) List<BillingEventStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "receivedAt"));
        return ResponseEntity.ok(service.list(organizationId, eventType, status, pageable));
    }

    @Operation(summary = "Retry one inbox row",
            description = "Hands the row back to the projector with a fresh attempt budget and returns it after the attempt. "
                    + "A row already processed is returned unchanged.")
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/{id}/retry")
    public ResponseEntity<BillingEventDto> retry(@PathVariable Long id) {
        return ResponseEntity.ok(service.retry(id));
    }
}
