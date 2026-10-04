package org.tornotron.echno_backend.risk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
import org.tornotron.echno_backend.risk.dto.RiskDto;
import org.tornotron.echno_backend.risk.dto.RiskImportRequest;
import org.tornotron.echno_backend.risk.dto.RiskRequest;

/**
 * Web-console twin of the risk register endpoints. Guards in {@link RiskAccess}: any member of the organization reads, the system
 * administrator and the project manager write.
 */
@RestController
@RequestMapping("/api/v1/project/{projectId}/risks/web")
@Tag(name = "Project risks (Web)", description = "A project's risk register: each risk with its category and "
        + "sub-category, probability and impact before and after the response, owner, dates and cost "
        + "and schedule impact. Any member of the organization reads the register; the system "
        + "administrator and the project manager record, change and remove risks.")
public class ProjectRiskControllerWeb {

    private final ProjectRiskService service;

    public ProjectRiskControllerWeb(ProjectRiskService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(RiskAccess.READ_GUARD)
    @Operation(summary = "List a project's risks", description = "Returns the project's whole register in R-number order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Register returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<List<RiskDto>> readRisks(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.list(projectId));
    }

    @GetMapping("/{riskId}")
    @PreAuthorize(RiskAccess.READ_GUARD)
    @Operation(summary = "Get a risk", description = "Returns one risk of the project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Risk returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project, or no such risk on it")
    })
    public ResponseEntity<RiskDto> readRisk(@PathVariable Long projectId, @PathVariable UUID riskId) {
        return ResponseEntity.ok(service.get(projectId, riskId));
    }

    @PostMapping
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Record a risk", description = "Adds a risk to the register with the project's next "
            + "R-number. The scores are worked out from probability and impact.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Risk recorded"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or project-manager role"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<RiskDto> createRisk(@PathVariable Long projectId, @Valid @RequestBody RiskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(projectId, request));
    }

    @PostMapping("/import")
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Import risks", description = "Adds up to 500 risks in one go, in the order given, "
            + "each with the next R-number. Meant for carrying over a register a browser held before the "
            + "register was kept on the server: a risk whose importRef the project already holds is "
            + "skipped, so repeating an import adds nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Risks imported; the body lists the ones added"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or project-manager role"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<List<RiskDto>> importRisks(@PathVariable Long projectId,
                                                     @Valid @RequestBody RiskImportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importRisks(projectId, request));
    }

    @PutMapping("/{riskId}")
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Change a risk", description = "Replaces the risk's fields. Send the version you "
            + "read; if someone else saved in between, the update is refused with a 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Risk changed"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or project-manager role"),
            @ApiResponse(responseCode = "404", description = "No such project, or no such risk on it"),
            @ApiResponse(responseCode = "409", description = "The risk changed since the given version")
    })
    public ResponseEntity<RiskDto> updateRisk(@PathVariable Long projectId, @PathVariable UUID riskId,
                                              @Valid @RequestBody RiskRequest request) {
        return ResponseEntity.ok(service.update(projectId, riskId, request));
    }

    @DeleteMapping("/{riskId}")
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Remove a risk", description = "Deletes the risk from the register.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Risk removed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or project-manager role"),
            @ApiResponse(responseCode = "404", description = "No such project, or no such risk on it")
    })
    public ResponseEntity<Void> deleteRisk(@PathVariable Long projectId, @PathVariable UUID riskId) {
        service.delete(projectId, riskId);
        return ResponseEntity.noContent().build();
    }
}
