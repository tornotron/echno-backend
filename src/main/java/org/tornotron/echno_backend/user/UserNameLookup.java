package org.tornotron.echno_backend.user;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The display names for a whole page of user ids, read once and handed to the mapper.
 *
 * <p>Documents stamp who did something as a user id: {@code submittedBy}, {@code approvedBy},
 * {@code rejectedBy}, {@code processedBy}. Nothing in the API turned one back into a name, so the
 * web app tried the employee lookup, which is keyed by employee id and carries no user id at all.
 * That missed for most ids and, where an employee id happened to equal a user id, put a different
 * person's name against an approval. Rendering {@code User #<id>} instead (echno-web#346, #349)
 * stopped the wrong name but left a number on the screen.
 *
 * <p>This is the batched read that resolves them without putting a query in the conversion path.
 * The rule from #522 is that a mapper converts the object it was handed and asks nobody anything,
 * enforced by {@code MapperDatabaseAccessTest}: a lookup done per row costs a round trip per row
 * and the call site shows none of it. So the service collects every user id on the page, resolves
 * them in one query, and passes the answer down as a MapStruct {@code @Context}, exactly as
 * {@code MaterialStockLookup} does for stock.
 *
 * <p>{@link #nameOf} never returns null for a non-null id. A stamp on a historical document has to
 * render even when the user row behind it is gone, and an id that resolves to nothing would
 * otherwise leave a blank cell where an approver's name belongs. The placeholder says plainly that
 * the account no longer exists rather than implying nobody approved the document.
 */
public final class UserNameLookup {

    private static final UserNameLookup EMPTY = new UserNameLookup(Map.of());

    private final Map<Long, String> byUserId;

    private UserNameLookup(Map<Long, String> byUserId) {
        this.byUserId = byUserId;
    }

    /**
     * A lookup holding nothing, so every id falls back to its placeholder.
     *
     * <p>For the paths that map a document which carries no stamps yet, and for tests.
     *
     * @return The empty lookup.
     */
    public static UserNameLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of a batched read.
     *
     * @param rows The user rows found, at most one per id. Rows with no usable name are dropped,
     *             so they read as an unresolved id rather than as a blank.
     * @return A lookup over those rows.
     */
    public static UserNameLookup of(Collection<UserDisplayName> rows) {
        if (rows == null || rows.isEmpty()) {
            return EMPTY;
        }
        Map<Long, String> names = rows.stream()
                .filter(row -> row.id() != null && row.bestEffortName() != null)
                .collect(Collectors.toMap(UserDisplayName::id, UserDisplayName::bestEffortName,
                        (first, second) -> first));
        return names.isEmpty() ? EMPTY : new UserNameLookup(names);
    }

    /**
     * The name to show against a user id.
     *
     * @param userId The stamped user id, which is null where the document was never submitted,
     *               approved or rejected.
     * @return The user's name; the placeholder {@code User #<id>} where the id does not resolve,
     *         which is what a deleted account leaves behind; null only where the id itself is null.
     */
    public String nameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        String name = byUserId.get(userId);
        return name != null ? name : placeholderFor(userId);
    }

    /**
     * What stands in for a user id that no longer resolves to a row.
     *
     * @param userId The unresolved user id.
     * @return The placeholder.
     */
    public static String placeholderFor(Long userId) {
        return "User #" + userId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserNameLookup lookup && byUserId.equals(lookup.byUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byUserId);
    }

    @Override
    public String toString() {
        return "UserNameLookup" + byUserId.keySet();
    }
}
