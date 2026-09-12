package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;

/**
 * The one door for machine producers. A separate controller from the web one so its
 * authorization and, later, its rate limit can differ: the caller is a Keycloak
 * client-credentials service account per fleet or integration, a member of the organisation
 * with the {@code observation-producer} role, and it sends the tenant header like any client.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/observations")
@RequiredArgsConstructor
@Tag(
        name = "Observation intake",
        description = "Machine producers post findings here: drones, ground robots, fixed cameras and "
                + "models, each authenticated as a service account of the organisation. Every finding "
                + "lands pending; a person reviews it through the inspections web endpoints. Idempotent "
                + "on the producer's externalRef."
)
public class ObservationIntakeController {

    private final ObservationService service;

    @PostMapping("/intake")
    @PreAuthorize("@inspectionSecurity.canIntakeObservations()")
    @Operation(summary = "Post a machine observation",
            description = "Records the finding as pending review. A repeat with the same externalRef "
                    + "returns the row already recorded, with 200 instead of 201, and changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Observation recorded, pending review"),
            @ApiResponse(responseCode = "200", description = "An observation with this externalRef already exists; returned unchanged"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the source is HUMAN"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the producer role in the current tenant, or the tenant is not entitled to the module"),
            @ApiResponse(responseCode = "404", description = "No spatial node with the given id in the project")
    })
    public ResponseEntity<ObservationDto> intake(@Valid @RequestBody IntakeObservationRequest req) {
        ObservationService.IntakeResult result = service.intake(req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.observation());
    }
}
