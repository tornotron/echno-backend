package org.tornotron.echno_backend.modules.access.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;

/**
 * Reads the permissions a {@code @PreAuthorize} expression names through {@code @access.can}.
 *
 * <p>Only {@code @access.can} counts. A {@code hasAuthority('finance:read')} guard names a
 * permission too, but the matrix does not decide it, so a grant of that permission would change
 * nothing, and the screen must not offer it as if it did.
 */
public final class AccessGuardExpressions {

    private static final Pattern CALL = Pattern.compile("@access\\s*\\.\\s*can\\s*\\(([^)]*)\\)");
    private static final Pattern TWO_LITERALS = Pattern.compile("\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*");

    /**
     * What one expression names.
     *
     * @param permissions the well-formed permissions
     * @param malformed   the {@code @access.can} calls that are not two literals forming a valid key
     */
    public record Parsed(Set<AccessPermission> permissions, List<String> malformed) {
    }

    private AccessGuardExpressions() {
    }

    public static Parsed parse(String expression) {
        Set<AccessPermission> permissions = new LinkedHashSet<>();
        List<String> malformed = new ArrayList<>();
        Matcher call = CALL.matcher(expression);
        while (call.find()) {
            Matcher literals = TWO_LITERALS.matcher(call.group(1));
            if (!literals.matches()) {
                malformed.add(call.group());
                continue;
            }
            try {
                permissions.add(new AccessPermission(literals.group(1), literals.group(2)));
            } catch (IllegalArgumentException e) {
                malformed.add(call.group());
            }
        }
        return new Parsed(permissions, malformed);
    }
}
