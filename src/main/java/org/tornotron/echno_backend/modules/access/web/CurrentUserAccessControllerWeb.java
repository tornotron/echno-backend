package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
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

    static final String MEMBER_GUARD = "@access.isMember()";

    private final CurrentAccessSubject current;
    private final AccessService access;

    public CurrentUserAccessControllerWeb(CurrentAccessSubject current, AccessService access) {
        this.current = current;
        this.access = access;
    }

    @GetMapping("/current-user-access")
    @PreAuthorize(MEMBER_GUARD)
    @Operation(
            summary = "Get the current user's access",
            description = "The caller's membership in the current organization and every permission "
                    + "they hold, as <area>:<action>, with the scopes each covers: ALL, "
                    + "ASSIGNED_PROJECTS, RELATED or OWN. Permissions held only on one project are "
                    + "listed under that project."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's access"),
            @ApiResponse(responseCode = "403", description = "Caller is not an active member of the current organization")
    })
    public ResponseEntity<CurrentUserAccessDto> getCurrentUserAccess() {
        return current.resolve()
                .map(subject -> {
                    AccessGrants held = access.grantsFor(subject);
                    Map<Long, Map<String, List<String>>> onProjects = new TreeMap<>();
                    held.projectScopes().forEach((project, scopes) -> onProjects.put(project, named(scopes)));
                    return ResponseEntity.ok(new CurrentUserAccessDto(subject.organizationId(), subject.employeeId(),
                            named(held.scopes()), onProjects));
                })
                .orElseGet(() -> ResponseEntity.status(403).build());
    }

    private static Map<String, List<String>> named(Map<String, Set<AccessScope>> scopes) {
        Map<String, List<String>> named = new TreeMap<>();
        scopes.forEach((key, held) -> named.put(key, held.stream().map(AccessScope::name).sorted().toList()));
        return named;
    }
}
