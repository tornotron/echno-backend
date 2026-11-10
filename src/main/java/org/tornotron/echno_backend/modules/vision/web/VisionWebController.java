package org.tornotron.echno_backend.modules.vision.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.dto.QueueVisionRunRequest;
import org.tornotron.echno_backend.modules.vision.dto.QueueVisionRunResult;
import org.tornotron.echno_backend.modules.vision.dto.UpdateVisionSettingsRequest;
import org.tornotron.echno_backend.modules.vision.dto.VisionSettingsDto;
import org.tornotron.echno_backend.modules.vision.service.VisionRunService;
import org.tornotron.echno_backend.modules.vision.service.VisionSettingsService;

@RestController
@VisionModuleEnabled
@RequireSubscription(feature = VisionModule.FEATURE_KEY)
@RequestMapping("/api/v1/vision/web")
@RequiredArgsConstructor
@Tag(name = "Vision runs", description = "An organization's computer vision settings, and running its models on "
        + "inspection and observation evidence images. Detections come back as pending observations.")
public class VisionWebController {

    private final VisionSettingsService settings;
    private final VisionRunService runs;

    @GetMapping("/settings")
    @PreAuthorize("@access.can('vision', 'read')")
    @Operation(summary = "Read the vision settings",
            description = "Whether new evidence images are run automatically, and which model families.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings returned"),
            @ApiResponse(responseCode = "402", description = "The organization is not entitled to the module, in enforce mode"),
            @ApiResponse(responseCode = "403", description = "Caller may not read computer vision")
    })
    public VisionSettingsDto getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("@access.can('vision', 'settings-manage')")
    @Operation(summary = "Replace the vision settings", description = "For the organization's system-admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings saved"),
            @ApiResponse(responseCode = "400", description = "A model key names no registered family"),
            @ApiResponse(responseCode = "402", description = "The organization is not entitled to the module, in enforce mode"),
            @ApiResponse(responseCode = "403", description = "Caller may not make this change to computer vision")
    })
    public VisionSettingsDto updateSettings(@Valid @RequestBody UpdateVisionSettingsRequest req) {
        return settings.update(req);
    }

    @PostMapping("/runs")
    @PreAuthorize("@access.can('vision', 'run')")
    @Operation(summary = "Run models on evidence images",
            description = "Queues one job per image and model version, up to 200 images. A pair with a pending job "
                    + "is skipped, and so is one that already ran unless rerun is true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jobs queued"),
            @ApiResponse(responseCode = "400", description = "No source or more than one, too many images, or no active "
                    + "model version to run"),
            @ApiResponse(responseCode = "402", description = "The organization is not entitled to the module, in enforce mode"),
            @ApiResponse(responseCode = "403", description = "Caller may not make this change to computer vision"),
            @ApiResponse(responseCode = "404", description = "The inspection, observation or an attachment is not evidence "
                    + "of this organization")
    })
    public QueueVisionRunResult queueRun(@Valid @RequestBody QueueVisionRunRequest req) {
        return runs.queueRun(req);
    }
}
