package org.tornotron.echno_backend.modules.vision.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.dto.ChangeCvModelVersionStatusRequest;
import org.tornotron.echno_backend.modules.vision.dto.CreateCvModelRequest;
import org.tornotron.echno_backend.modules.vision.dto.CvModelDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelVersionDto;
import org.tornotron.echno_backend.modules.vision.dto.RegisterCvModelVersionRequest;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;

/**
 * Writes to the registry. The catalogue is global, so this is a platform surface, guarded by the
 * platform-admin role as the billing plan catalogue is: an organization's system-admin is whoever
 * created that organization, and must not decide which model every organization runs. For the same
 * reason it carries no subscription gate: a platform admin has no tenant to be entitled.
 */
@RestController
@VisionModuleEnabled
@RequestMapping("/api/v1/vision/admin/models")
@RequiredArgsConstructor
@Tag(name = "Vision model administration", description = "Registers model families and versions and activates "
        + "them. Platform operators only.")
public class VisionModelAdminController {

    private final CvModelRegistryService service;

    @PostMapping
    @PreAuthorize(VisionModule.MODELS_MANAGE_GUARD)
    @Operation(summary = "Create a model family")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Family created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @ApiResponse(responseCode = "409", description = "A family with this key already exists")
    })
    public ResponseEntity<CvModelDto> create(@Valid @RequestBody CreateCvModelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createModel(req));
    }

    @PostMapping("/{key}/versions")
    @PreAuthorize(VisionModule.MODELS_MANAGE_GUARD)
    @Operation(summary = "Register a model version", description = "Lands DRAFT. Its classes must be in the class "
            + "taxonomy with the taxonomy's shape, and must suit the family's task.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Version registered as DRAFT"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or a class is not in the taxonomy"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @ApiResponse(responseCode = "404", description = "No such model"),
            @ApiResponse(responseCode = "409", description = "This version already exists")
    })
    public ResponseEntity<CvModelVersionDto> register(@PathVariable String key,
                                                      @Valid @RequestBody RegisterCvModelVersionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerVersion(key, req));
    }

    @PostMapping("/{key}/versions/{version}/status")
    @PreAuthorize(VisionModule.MODELS_MANAGE_GUARD)
    @Operation(summary = "Activate or retire a model version", description = "Activation refuses a licence that is "
            + "not on echno.vision.allowed-licences, or a version with no metrics or evaluation report, and retires "
            + "the version active before it. Retired is terminal.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status changed"),
            @ApiResponse(responseCode = "400", description = "The target is DRAFT, or the version does not qualify for activation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @ApiResponse(responseCode = "404", description = "No such model or version"),
            @ApiResponse(responseCode = "409", description = "The version is retired, or already active")
    })
    public CvModelVersionDto changeStatus(@PathVariable String key, @PathVariable String version,
                                          @Valid @RequestBody ChangeCvModelVersionStatusRequest req) {
        return service.changeStatus(key, version, req);
    }
}
