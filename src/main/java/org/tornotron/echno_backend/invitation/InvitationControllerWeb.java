package org.tornotron.echno_backend.invitation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.invitation.dto.InvitationCreationDto;
import org.tornotron.echno_backend.invitation.dto.InvitationDto;

import java.util.List;
import java.util.UUID;

/**
 * Invitations to join the caller's organization. Guarded on the organization roles that may add an
 * employee today, until the {@code org-admin} area moves onto the access matrix.
 */
@RestController
@RequestMapping("/api/v1/invitations/web")
@Validated
@Tag(name = "Invitations",
        description = "Invitations by link to join the caller's organization, with the roles the person gets "
                + "and the employee details the join form collects. The identity provider sends the email. "
                + "Nobody becomes an employee until they accept with the invited address.")
public class InvitationControllerWeb {

    static final String GUARD = "@orgSecurity.isMemberOfCurrentTenant()"
            + " and @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')";

    private final InvitationService invitations;

    public InvitationControllerWeb(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @PreAuthorize(GUARD)
    @Operation(summary = "Invite a person",
            description = "Records the invitation and has the identity provider send the link. Each role is "
                    + "checked as an assignment by the caller would be.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invitation recorded; the link follows"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, or a role cannot be given on a project"),
            @ApiResponse(responseCode = "403", description = "Caller may not invite, may not give one of the roles, or invited their own address"),
            @ApiResponse(responseCode = "404", description = "A role or project is not in this organization"),
            @ApiResponse(responseCode = "409", description = "The address is already an employee here or has an open invitation")
    })
    public ResponseEntity<InvitationDto> invite(@Valid @RequestBody InvitationCreationDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.invite(request));
    }

    @GetMapping
    @PreAuthorize(GUARD)
    @Operation(summary = "List invitations", description = "The organization's invitations, newest first.")
    public ResponseEntity<List<InvitationDto>> list(@Valid @ParameterObject PageQuery pageQuery) {
        return ResponseEntity.ok(invitations.list(pageQuery));
    }

    @GetMapping("/{id}")
    @PreAuthorize(GUARD)
    @Operation(summary = "Read one invitation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invitation returned"),
            @ApiResponse(responseCode = "404", description = "No such invitation in this organization")
    })
    public ResponseEntity<InvitationDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(invitations.get(id));
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize(GUARD)
    @Operation(summary = "Resend an invitation",
            description = "Sends the same link again and keeps its expiry. An expired invitation is renewed: it "
                    + "gets a new expiry and a new link, and the old link stops working.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resend or renewal requested"),
            @ApiResponse(responseCode = "400", description = "The invitation was accepted or revoked"),
            @ApiResponse(responseCode = "404", description = "No such invitation in this organization")
    })
    public ResponseEntity<InvitationDto> resend(@PathVariable UUID id) {
        return ResponseEntity.ok(invitations.resend(id));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize(GUARD)
    @Operation(summary = "Revoke an invitation", description = "Stops the link working.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invitation revoked"),
            @ApiResponse(responseCode = "400", description = "The invitation was already accepted or revoked"),
            @ApiResponse(responseCode = "404", description = "No such invitation in this organization")
    })
    public ResponseEntity<InvitationDto> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok(invitations.revoke(id));
    }
}
