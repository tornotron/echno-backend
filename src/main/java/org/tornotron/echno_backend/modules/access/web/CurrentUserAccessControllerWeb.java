package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.web.dto.CurrentUserAccessDto;

/**
 * The caller's own access in the current organization, which the web and mobile apps read on
 * session start to decide which areas and actions to show.
 */
@RestController
@RequestMapping("/api/v1/access/web")
@Tag(
        name = "Access (Web)",
        description = "What the caller may do in their current organization: every permission they "
                + "hold and the records each covers. For deciding what to show; the server checks "
                + "every request on its own."
)
public class CurrentUserAccessControllerWeb {

    private final CurrentAccessSubject current;
    private final AccessService access;

    public CurrentUserAccessControllerWeb(CurrentAccessSubject current, AccessService access) {
        this.current = current;
        this.access = access;
    }

    @GetMapping("/me")
    @PreAuthorize("@access.isMember()")
    @Operation(
            summary = "Get the current user's access",
            description = "The caller's membership in the current organization and every permission "
                    + "they hold, as <area>:<action>, with the scopes each covers: ALL, "
                    + "ASSIGNED_PROJECTS, RELATED or OWN."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's access"),
            @ApiResponse(responseCode = "403", description = "Caller is not an active member of the current organization")
    })
    public ResponseEntity<CurrentUserAccessDto> getCurrentUserAccess() {
        return current.resolve()
                .map(subject -> {
                    Map<String, List<String>> grants = new TreeMap<>();
                    access.grantsFor(subject).scopes().forEach((key, scopes) ->
                            grants.put(key, scopes.stream().map(AccessScope::name).sorted().toList()));
                    return ResponseEntity.ok(new CurrentUserAccessDto(subject.organizationId(), subject.employeeId(), grants));
                })
                .orElseGet(() -> ResponseEntity.status(403).build());
    }
}
