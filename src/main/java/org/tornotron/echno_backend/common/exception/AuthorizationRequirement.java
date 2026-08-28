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
 * so a caller is told that something was refused but never what. The expression that did the
 * refusing is available on the failure itself, and it is the one place that states the requirement
 * exactly. This parses it into the role and permission names it names, so the API can say what was
 * missing instead of only that something was.
 *
 * <p>The parse keeps the shape of the expression as well as its names. An expression is first split
 * on its top-level {@code or} into the alternatives that each independently grant access; within an
 * alternative, everything found is required together. That distinction matters: a guard reading
 * {@code hasAuthority('organization:delete') and @orgSecurity.isMember(#id)} grants nothing to a
 * caller holding only the permission, and a message offering the two as separate routes would send
 * them back for a second refusal.
 *
 * <p>Within an alternative the parse is deliberately shallow: it walks the {@code name(arguments)}
 * calls and keeps the quoted literals from the ones it recognises. An expression it does not
 * recognise yields {@link #isDescribable()} false, and the caller falls back to the generic message
 * rather than guessing.
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

    /**
     * One route through the guard: everything it names has to hold at the same time.
     *
     * @param organizationRoles org-scoped roles, any one of which satisfies the role part.
     * @param authorities global authorities, any one of which satisfies the permission part.
     * @param organizationMembership whether membership of the organization is also required.
     * @param ownRecord whether owning the record is also accepted.
     */
    private record Alternative(List<String> organizationRoles, List<String> authorities,
                               boolean organizationMembership, boolean ownRecord) {

        boolean isEmpty() {
            return organizationRoles.isEmpty() && authorities.isEmpty() && !organizationMembership && !ownRecord;
        }

        /** This route in words, with its parts joined by "and" because all of them are needed. */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (!organizationRoles.isEmpty()) {
                parts.add(joinQuoted(organizationRoles)
                        + (organizationRoles.size() == 1 ? " role" : " roles")
                        + " in this organization");
            }
            if (!authorities.isEmpty()) {
                parts.add(joinQuoted(authorities)
                        + (authorities.size() == 1 ? " permission" : " permissions"));
            }
            if (organizationMembership) {
                parts.add("membership of this organization");
            }
            if (ownRecord) {
                parts.add("that the record belongs to you");
            }
            return String.join(" and ", parts);
        }
    }

    private final List<Alternative> alternatives;

    private AuthorizationRequirement(List<Alternative> alternatives) {
        this.alternatives = List.copyOf(alternatives);
    }

    /**
     * Reads the requirement out of a {@code @PreAuthorize} expression.
     *
     * @param expression the expression as written on the endpoint, may be null.
     * @return the requirement it states, empty of everything when the expression says nothing
     *         this knows how to name.
     */
    public static AuthorizationRequirement from(String expression) {
        List<Alternative> parsed = new ArrayList<>();
        if (expression != null) {
            for (String alternative : splitOnTopLevelOr(expression)) {
                Alternative read = readAlternative(alternative);
                if (!read.isEmpty()) {
                    parsed.add(read);
                }
            }
        }
        return new AuthorizationRequirement(parsed);
    }

    /** Whether the expression named anything worth telling the caller about. */
    public boolean isDescribable() {
        return !alternatives.isEmpty();
    }

    /** Every org-scoped role name the expression mentions, in the order it lists them. */
    public List<String> getOrganizationRoles() {
        return alternatives.stream()
                .flatMap(alternative -> alternative.organizationRoles().stream())
                .distinct()
                .toList();
    }

    /** Every global authority name the expression mentions, in the order it lists them. */
    public List<String> getAuthorities() {
        return alternatives.stream()
                .flatMap(alternative -> alternative.authorities().stream())
                .distinct()
                .toList();
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

        String routes = String.join(", or ", alternatives.stream().map(Alternative::describe).toList());
        StringBuilder sentence = new StringBuilder("This action requires ").append(routes).append(".");

        boolean organizationScoped = alternatives.stream()
                .anyMatch(alternative -> !alternative.organizationRoles().isEmpty()
                        || alternative.organizationMembership());
        if (organizationScoped) {
            sentence.append(" Your roles are read from the session you signed in with, so if one was "
                    + "granted just now, sign out and back in to pick it up.");
        }
        return sentence.toString();
    }

    /**
     * Splits an expression on the {@code or} operators that sit outside any bracket.
     *
     * <p>Only a top-level {@code or} separates two independent routes through the guard. An
     * {@code or} nested inside brackets belongs to whichever clause encloses it, and splitting on
     * it would break a conjunction apart and turn "both of these" into "either of these".
     */
    private static List<String> splitOnTopLevelOr(String expression) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inLiteral = false;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && c == '(') {
                depth++;
            } else if (!inLiteral && c == ')') {
                depth--;
            } else if (!inLiteral && depth == 0 && isOperatorAt(expression, i, "or")) {
                parts.add(expression.substring(start, i));
                start = i + 2;
            } else if (!inLiteral && depth == 0 && isOperatorAt(expression, i, "||")) {
                parts.add(expression.substring(start, i));
                start = i + 2;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    /** Whether the token at this position is the given operator standing on its own. */
    private static boolean isOperatorAt(String expression, int index, String operator) {
        if (!expression.startsWith(operator, index)) {
            return false;
        }
        boolean beforeIsBoundary = index == 0 || !Character.isLetterOrDigit(expression.charAt(index - 1));
        int after = index + operator.length();
        boolean afterIsBoundary = after >= expression.length()
                || !Character.isLetterOrDigit(expression.charAt(after));
        return beforeIsBoundary && afterIsBoundary;
    }

    /** Reads one alternative, treating everything it names as required together. */
    private static Alternative readAlternative(String expression) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        boolean membership = false;
        boolean self = false;

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

        return new Alternative(new ArrayList<>(roles), new ArrayList<>(permissions), membership, self);
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
