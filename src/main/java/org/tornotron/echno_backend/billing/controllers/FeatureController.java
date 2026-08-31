package org.tornotron.echno_backend.billing.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.FeatureCreateDto;
import org.tornotron.echno_backend.billing.dto.FeatureDto;
import org.tornotron.echno_backend.billing.services.FeatureService;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing/features/web")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing Features",
        description = "Individually toggleable capabilities that a plan can grant, such as report exports "
                + "or a device quota. A feature has a type (boolean, quota or metered) and, once deactivated, "
                + "is no longer offered on new plans. The feature catalogue is platform-wide rather than "
                + "per-customer, so every endpoint here is restricted to the platform-admin role."
)
public class FeatureController {

    private final FeatureService featureService;

    /**
     * Retrieves all active features.
     * Available for viewing features that can be included in plans.
     *
     * @return List of active features
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping
    @Operation(
            summary = "List active features",
            description = "Returns the features that are currently active and available for assignment to "
                    + "plans."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of active features"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<List<FeatureDto>> getActiveFeatures() {
        List<Feature> features = featureService.getAllActiveFeatures();
        return ResponseEntity.ok(BillingMapper.toFeatureDtoList(features));
    }

    /**
     * Retrieves all features including inactive ones.
     * Admin only.
     *
     * @return List of all features
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping("/all")
    @Operation(
            summary = "List all features",
            description = "Returns every feature, including deactivated ones, for administration."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of all features"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<List<FeatureDto>> getAllFeatures() {
        List<Feature> features = featureService.getAllFeatures();
        return ResponseEntity.ok(BillingMapper.toFeatureDtoList(features));
    }

    /**
     * Retrieves a specific feature by ID.
     *
     * @param id Feature ID
     * @return Feature details
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping("/{id}")
    @Operation(
            summary = "Get a feature by id",
            description = "Returns a single feature by its numeric id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given id")
    })
    public ResponseEntity<FeatureDto> getFeatureById(@PathVariable Long id) {
        Feature feature = featureService.getFeatureById(id);
        return ResponseEntity.ok(BillingMapper.toFeatureDto(feature));
    }

    /**
     * Retrieves a specific feature by code.
     *
     * @param code Feature code
     * @return Feature details
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping("/code/{code}")
    @Operation(
            summary = "Get a feature by code",
            description = "Returns a single feature by its unique code, such as report-export."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given code")
    })
    public ResponseEntity<FeatureDto> getFeatureByCode(@PathVariable String code) {
        Feature feature = featureService.getFeatureByCode(code);
        return ResponseEntity.ok(BillingMapper.toFeatureDto(feature));
    }

    /**
     * Creates a new feature.
     * Admin only.
     *
     * @param dto Feature creation data
     * @return Created feature
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping
    @Operation(
            summary = "Create a feature",
            description = "Creates a new feature that can subsequently be assigned to plans."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Feature created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<FeatureDto> createFeature(@Valid @RequestBody FeatureCreateDto dto) {
        Feature feature = featureService.createFeature(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(BillingMapper.toFeatureDto(feature));
    }

    /**
     * Updates an existing feature.
     * Admin only.
     *
     * @param id  Feature ID
     * @param dto Feature update data
     * @return Updated feature
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PutMapping("/{id}")
    @Operation(
            summary = "Update a feature",
            description = "Updates the details of an existing feature identified by id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given id")
    })
    public ResponseEntity<FeatureDto> updateFeature(@PathVariable Long id, @Valid @RequestBody FeatureCreateDto dto) {
        Feature feature = featureService.updateFeature(id, dto);
        return ResponseEntity.ok(BillingMapper.toFeatureDto(feature));
    }

    /**
     * Deactivates a feature (soft delete).
     * Admin only.
     *
     * @param id Feature ID
     * @return Success message
     */
    @PreAuthorize("hasRole('platform-admin')")
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Deactivate a feature",
            description = "Soft-deletes a feature so it can no longer be assigned to new plans. Plans that "
                    + "already include it are left unchanged."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given id")
    })
    public ResponseEntity<ApiResponse> deactivateFeature(@PathVariable Long id) {
        featureService.deactivateFeature(id);
        return ResponseEntity.ok(new ApiResponse("Feature deactivated successfully"));
    }

    /**
     * Reactivates a deactivated feature.
     * Admin only.
     *
     * @param id Feature ID
     * @return Success message
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/{id}/activate")
    @Operation(
            summary = "Reactivate a feature",
            description = "Reactivates a previously deactivated feature so it can be assigned to plans again."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature activated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given id")
    })
    public ResponseEntity<ApiResponse> activateFeature(@PathVariable Long id) {
        featureService.activateFeature(id);
        return ResponseEntity.ok(new ApiResponse("Feature activated successfully"));
    }
}
