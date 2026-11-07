package org.tornotron.echno_backend.modules.vision.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.dto.CvModelDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelVersionDto;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;

@RestController
@VisionModuleEnabled
@RequireSubscription(feature = VisionModule.FEATURE_KEY)
@RequestMapping("/api/v1/vision/models")
@RequiredArgsConstructor
@Tag(name = "Vision models", description = "The computer vision model registry: model families and their versions. "
        + "A platform-wide catalogue, read here by an organization entitled to the module.")
public class VisionModelController {

    private final CvModelRegistryService service;

    @GetMapping
    @PreAuthorize(VisionModule.READ_GUARD)
    @Operation(summary = "List model families", description = "Every family with its versions, oldest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Families returned"),
            @ApiResponse(responseCode = "402", description = "The organization is not entitled to the module, in enforce mode"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<CvModelDto> list() {
        return service.listModels();
    }

    @GetMapping("/{key}/versions/{version}")
    @PreAuthorize(VisionModule.READ_GUARD)
    @Operation(summary = "Read a model version")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version returned"),
            @ApiResponse(responseCode = "402", description = "The organization is not entitled to the module, in enforce mode"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such model or version")
    })
    public CvModelVersionDto version(@PathVariable String key, @PathVariable String version) {
        return service.getVersion(key, version);
    }
}
