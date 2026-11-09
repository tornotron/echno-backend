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
 * <p>Only {@code @access.can} and {@code @access.canIn} count; {@code canIn}'s first argument is the
 * organization and the two literals after it name the permission. A {@code hasAuthority('finance:read')} guard names a
 * permission too, but the matrix does not decide it, so a grant of that permission would change
 * nothing, and the screen must not offer it as if it did.
 */
public final class AccessGuardExpressions {

    private static final Pattern CALL = Pattern.compile("@access\\s*\\.\\s*can\\s*\\(([^)]*)\\)");
    private static final Pattern CALL_IN = Pattern.compile("@access\\s*\\.\\s*canIn\\s*\\(([^)]*)\\)");
    /** {@code canIn}'s first argument names the organization, as an expression; the two literals follow it. */
    private static final Pattern ORGANIZATION_FIRST = Pattern.compile("\\s*[^,']+,(.*)");
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
        collect(CALL.matcher(expression), false, permissions, malformed);
        collect(CALL_IN.matcher(expression), true, permissions, malformed);
        return new Parsed(permissions, malformed);
    }

    private static void collect(Matcher call, boolean organizationFirst, Set<AccessPermission> permissions,
                                List<String> malformed) {
        while (call.find()) {
            String arguments = call.group(1);
            if (organizationFirst) {
                Matcher organization = ORGANIZATION_FIRST.matcher(arguments);
                if (!organization.matches()) {
                    malformed.add(call.group());
                    continue;
                }
                arguments = organization.group(1);
            }
            Matcher literals = TWO_LITERALS.matcher(arguments);
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
    }
}
