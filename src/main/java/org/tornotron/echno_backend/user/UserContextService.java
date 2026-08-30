package org.tornotron.echno_backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserContextService {

    private final UserRepository userRepository;

//    @Cacheable(value = "userContext", key = "#root.methodName + '_' + @userContextService.getCurrentKeycloakId()")
    public Long getCurrentUserId() {
        String keycloakId = getCurrentKeycloakId();
        if (keycloakId == null) {
            return null;
        }

        return userRepository.findUserByKeycloakId(keycloakId)
                .map(User::getId)
                .orElse(null);
    }

    public Long getCurrentUserIdOrThrow() throws AuthenticationException {
        Long userId = getCurrentUserId();
        if(userId == null) {
            throw new AuthenticationException("User not authenticated");
        }
        return userId;

    }

    public User getCurrentUser() {
        String keycloakId = getCurrentKeycloakId();
        if (keycloakId == null) {
            return null;
        }
        return userRepository.findUserByKeycloakId(keycloakId)
                .orElse(null);
    }

    public User getCurrentUserOrThrow() throws AuthenticationException {
        User user = getCurrentUser();
        if (user == null) {
            throw new AuthenticationException("User not authenticated or not found in database");
        }
        return user;
    }

    public String getCurrentKeycloakId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        if(principal instanceof Jwt) {
            Jwt jwt = (Jwt) principal;
            return jwt.getSubject();
        }

        String name = authentication.getName();
        if(name != null && !name.equals("anonymousUser")) {
            return name;
        }

        log.warn("Could not extract Keycloak ID from authentication principal of type: {}",
                principal.getClass().getName());
        return null;
    }

    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        if(authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getClaimAsString("email");
        }
        return null;
    }

    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        if(authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getClaimAsString("preferred_username");
        }

        return authentication.getName();
    }

    public String getCurrentUserGivenName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getClaimAsString("given_name");
        }

        return null;
    }

    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null &&
                authentication.isAuthenticated() &&
                !(authentication instanceof AnonymousAuthenticationToken);
    }

    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if(authentication == null) {
            return false;
        }

        String roleToCheck = role.startsWith("ROLE_") ? role : "ROLE_" + role;

        return authentication.getAuthorities().stream()
                .anyMatch(auth ->
                        auth.getAuthority().equals(roleToCheck) ||
                        auth.getAuthority().equals(role));
    }

    public boolean hasAnyRole(String... roles) {
        for(String role : roles) {
            if(hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAllRoles(String... roles) {
        for (String role : roles) {
            if(!hasRole(role)) {
                return false;
            }
        }
        return true;
    }

    public List<String> getCurrentUserRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication == null) {
            return Collections.emptyList();
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }

    public Object getCustomClaim(String claimName) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getClaim(claimName);
        }
        return null;
    }

    public Map<String ,Object> getAllClaims() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getClaims();
        }

        return Collections.emptyMap();
    }

    public String getJwtTokenValue() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return jwt.getTokenValue();
        }

        return null;
    }
}
