package org.tornotron.echno_backend.common.exception;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a {@code @PreAuthorize} guard asked for, read back out of its own expression.
 *
 * <p>Spring answers every denied {@code @PreAuthorize} with the same two words, "Access Denied",
 * so a caller is told that something was refused but never which permission would have let it
 * through. The expression that did the refusing is available on the failure itself, and it is the
 * one place that states the requirement exactly. This parses it into the role and permission names
 * it names, so the API can say what was missing instead of only that something was.
 *
 * <p>The parse is deliberately shallow: it walks the {@code name(arguments)} calls in the
 * expression and keeps the quoted literals from the ones it recognises. An expression it does not
 * recognise yields {@link #isDescribable()} false, and the caller falls back to the generic
 * message rather than guessing.
 *
 * <p>Nothing here reveals more than the endpoint's own annotation already does. It names the role
 * or permission an endpoint wants, never who holds it, and never anything about the caller.
 */
public final class AuthorizationRequirement {

    /** Matches a {@code name(arguments)} call, including a bean reference such as {@code @orgSecurity.hasOrgRole(...)}. */
    private static final Pattern CALL = Pattern.compile("([@A-Za-z][\\w.]*)\\s*\\(([^()]*)\\)");

    /** Matches a single-quoted literal inside an argument list. */
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    /** Calls whose quoted arguments are org-scoped role names. */
    private static final Set<String> ORG_ROLE_CALLS = Set.of(
            "hasOrgRole", "hasAnyOrgRole", "hasAnyOrgRoleForCurrentTenant", "isSelfOrHasAnyOrgRole");

    /** Calls whose quoted arguments are global authority names. */
    private static final Set<String> AUTHORITY_CALLS = Set.of(
            "hasAuthority", "hasAnyAuthority", "hasRole", "hasAnyRole");

    /** Calls that ask for membership of an organization rather than a role within one. */
    private static final Set<String> MEMBERSHIP_CALLS = Set.of(
            "isMember", "isMemberOrAdmin", "isMemberOfCurrentTenant");

    /** Calls that let the caller through on their own record. */
    private static final Set<String> SELF_CALLS = Set.of(
            "isSelfUser", "isSelfInCurrentTenant", "isSelfOrHasAnyOrgRole");

    private final List<String> organizationRoles;
    private final List<String> authorities;
    private final boolean organizationMembership;
    private final boolean ownRecord;

    private AuthorizationRequirement(List<String> organizationRoles, List<String> authorities,
                                     boolean organizationMembership, boolean ownRecord) {
        this.organizationRoles = List.copyOf(organizationRoles);
        this.authorities = List.copyOf(authorities);
        this.organizationMembership = organizationMembership;
        this.ownRecord = ownRecord;
    }

    /**
     * Reads the requirement out of a {@code @PreAuthorize} expression.
     *
     * @param expression the expression as written on the endpoint, may be null.
     * @return the requirement it states, empty of everything when the expression says nothing
     *         this knows how to name.
     */
    public static AuthorizationRequirement from(String expression) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        boolean membership = false;
        boolean self = false;

        if (expression != null) {
            Matcher call = CALL.matcher(expression);
            while (call.find()) {
                String name = simpleName(call.group(1));
                String arguments = call.group(2);

                if (ORG_ROLE_CALLS.contains(name)) {
                    roles.addAll(literals(arguments));
                }
                if (AUTHORITY_CALLS.contains(name)) {
                    permissions.addAll(literals(arguments));
                }
                if (MEMBERSHIP_CALLS.contains(name)) {
                    membership = true;
                }
                if (SELF_CALLS.contains(name)) {
                    self = true;
                }
            }
        }

        return new AuthorizationRequirement(new ArrayList<>(roles), new ArrayList<>(permissions), membership, self);
    }

    /** Whether the expression named anything worth telling the caller about. */
    public boolean isDescribable() {
        return !organizationRoles.isEmpty() || !authorities.isEmpty() || organizationMembership || ownRecord;
    }

    /** The org-scoped role names the endpoint accepts, in the order the expression lists them. */
    public List<String> getOrganizationRoles() {
        return organizationRoles;
    }

    /** The global authority names the endpoint accepts, in the order the expression lists them. */
    public List<String> getAuthorities() {
        return authorities;
    }

    /**
     * The requirement in a sentence a non-engineer can act on.
     *
     * @return the sentence, or null when the expression named nothing to describe.
     */
    public String describe() {
        if (!isDescribable()) {
            return null;
        }

        List<String> alternatives = new ArrayList<>();
        if (!organizationRoles.isEmpty()) {
            alternatives.add(joinQuoted(organizationRoles)
                    + (organizationRoles.size() == 1 ? " role" : " roles")
                    + " in this organization");
        }
        if (!authorities.isEmpty()) {
            alternatives.add(joinQuoted(authorities)
                    + (authorities.size() == 1 ? " permission" : " permissions"));
        }
        if (organizationMembership) {
            alternatives.add("membership of this organization");
        }
        if (ownRecord) {
            alternatives.add("that the record belongs to you");
        }

        StringBuilder sentence = new StringBuilder("This action requires ")
                .append(String.join(", or ", alternatives))
                .append(".");

        if (!organizationRoles.isEmpty() || organizationMembership) {
            sentence.append(" Your roles are read from the session you signed in with, so if one was "
                    + "granted just now, sign out and back in to pick it up.");
        }
        return sentence.toString();
    }

    /** Strips a bean reference and package qualification, leaving the method name. */
    private static String simpleName(String call) {
        int lastDot = call.lastIndexOf('.');
        return lastDot < 0 ? call.replace("@", "") : call.substring(lastDot + 1);
    }

    /** Every single-quoted literal in an argument list, in order. */
    private static List<String> literals(String arguments) {
        List<String> found = new ArrayList<>();
        Matcher literal = LITERAL.matcher(arguments);
        while (literal.find()) {
            String value = literal.group(1);
            if (!value.isBlank()) {
                found.add(value);
            }
        }
        return found;
    }

    /** Renders names as {@code the 'a', 'b' or 'c'}, which reads the same for one name or several. */
    private static String joinQuoted(List<String> names) {
        List<String> quoted = names.stream().map(name -> "'" + name + "'").toList();
        if (quoted.size() == 1) {
            return "the " + quoted.getFirst();
        }
        return "the " + String.join(", ", quoted.subList(0, quoted.size() - 1))
                + " or " + quoted.getLast();
    }
}
