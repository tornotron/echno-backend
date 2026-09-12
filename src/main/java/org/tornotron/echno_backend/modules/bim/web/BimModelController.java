package org.tornotron.echno_backend.modules.bim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.bim.BimModule;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.BimElementPageDto;
import org.tornotron.echno_backend.modules.bim.dto.BimImportJobDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelVersionDto;
import org.tornotron.echno_backend.modules.bim.dto.CreateBimModelRequest;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;

/**
 * Models, versions, elements and jobs. One surface under {@code /api/v1/bim}: new
 * controllers follow the collapsed shape, so there is no {@code /web} twin.
 */
@RestController
@RequestMapping("/api/v1/bim")
@RequireSubscription(feature = BimModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "BIM Models",
        description = "IFC models per project, their versions, the element table that keeps an IFC "
                + "GlobalId stable across re-imports, and the worker jobs that import them. All "
                + "endpoints are tenant scoped and gated on the BIM module.")
public class BimModelController {

    private final BimModelService service;

    @GetMapping("/projects/{projectId}/models")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List the BIM models of a project, each with its versions newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The models"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public List<BimModelDto> listForProject(@PathVariable Long projectId) {
        return service.listForProject(projectId);
    }

    @PostMapping("/projects/{projectId}/models")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Register a BIM model on a project")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Model created"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant"),
            @ApiResponse(responseCode = "409", description = "A model of that name already exists on the project")
    })
    public ResponseEntity<BimModelDto> create(@PathVariable Long projectId,
                                              @Valid @RequestBody CreateBimModelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(projectId, req));
    }

    @GetMapping("/models/{modelId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get a BIM model with its versions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The model"),
            @ApiResponse(responseCode = "404", description = "No such model in the current tenant")
    })
    public BimModelDto get(@PathVariable UUID modelId) {
        return service.get(modelId);
    }

    @GetMapping("/models/{modelId}/versions/{versionId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one version of a model")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The version"),
            @ApiResponse(responseCode = "404", description = "No such model or version in the current tenant")
    })
    public BimModelVersionDto getVersion(@PathVariable UUID modelId, @PathVariable UUID versionId) {
        return service.getVersion(modelId, versionId);
    }

    @GetMapping("/models/{modelId}/elements")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Page through the elements of a model",
            description = "Filter by storey GlobalId to load one floor. Retired elements (present in an "
                    + "earlier version, missing from the latest) are excluded unless asked for.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of elements"),
            @ApiResponse(responseCode = "404", description = "No such model in the current tenant")
    })
    public BimElementPageDto listElements(@PathVariable UUID modelId,
                                          @RequestParam(required = false) String storeyGlobalId,
                                          @RequestParam(defaultValue = "false") boolean includeRetired,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "200") int size) {
        return service.listElements(modelId, storeyGlobalId, includeRetired, page, size);
    }

    @GetMapping("/elements/{elementId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one element by its Echno id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The element"),
            @ApiResponse(responseCode = "404", description = "No such element in the current tenant")
    })
    public BimElementDto getElement(@PathVariable UUID elementId) {
        return service.getElement(elementId);
    }

    @GetMapping("/models/{modelId}/versions/{versionId}/jobs")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List the import jobs of a version, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The jobs"),
            @ApiResponse(responseCode = "404", description = "No such model or version in the current tenant")
    })
    public List<BimImportJobDto> listJobs(@PathVariable UUID modelId, @PathVariable UUID versionId) {
        return service.listJobs(modelId, versionId);
    }

    @PostMapping("/models/{modelId}/versions/{versionId}/jobs")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Queue (or re-queue after a failure) the worker import of a version")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Job queued"),
            @ApiResponse(responseCode = "400", description = "The version is not in a state that can be queued, or a job is already open"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such model or version in the current tenant")
    })
    public ResponseEntity<BimImportJobDto> enqueue(@PathVariable UUID modelId, @PathVariable UUID versionId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.enqueueImport(modelId, versionId));
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get an import job")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The job"),
            @ApiResponse(responseCode = "404", description = "No such job in the current tenant")
    })
    public BimImportJobDto getJob(@PathVariable UUID jobId) {
        return service.getJob(jobId);
    }
}
