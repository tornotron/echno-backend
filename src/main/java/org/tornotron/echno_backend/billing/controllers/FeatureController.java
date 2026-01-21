package org.tornotron.echno_backend.billing.controllers;

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
public class FeatureController {

    private final FeatureService featureService;

    /**
     * Retrieves all active features.
     * Available for viewing features that can be included in plans.
     *
     * @return List of active features
     */
    @GetMapping
//    @PreAuthorize("hasAuthority('billing:read') or hasAuthority('billing:admin')")
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
    @GetMapping("/all")
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @GetMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:read') or hasAuthority('billing:admin')")
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
    @GetMapping("/code/{code}")
//    @PreAuthorize("hasAuthority('billing:read') or hasAuthority('billing:admin')")
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
    @PostMapping
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @PutMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @DeleteMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @PostMapping("/{id}/activate")
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<ApiResponse> activateFeature(@PathVariable Long id) {
        featureService.activateFeature(id);
        return ResponseEntity.ok(new ApiResponse("Feature activated successfully"));
    }
}
