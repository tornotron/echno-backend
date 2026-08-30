package org.tornotron.echno_backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves user ids to the names shown against them on a document.
 *
 * <p>Its own service rather than a method on {@link UserService} because the two answer different
 * questions. {@code UserService} manages accounts: it provisions Keycloak identities, stores
 * attachments and enforces who may read whom. This reads three columns so a screen can print a
 * name, and every service that maps a document with an approval stamp needs it, so it is kept
 * small and free of everything those callers would otherwise drag in.
 *
 * <p>The read is deliberately not scoped to the caller's tenant. A stamp is only ever reached
 * through a document the caller has already been authorised to read, and the person who approved
 * it may since have left the organization; scoping the name would blank exactly the historical
 * records the stamp exists for. What comes back is a display name and nothing else, which is
 * already visible to anyone who can read the document.
 */
@Service
public class UserNameDirectory {

    private final UserRepository userRepository;

    /**
     * Constructs the directory.
     *
     * @param userRepository The repository the names are read from.
     */
    public UserNameDirectory(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Reads the display names for a whole set of user ids in one query.
     *
     * <p>This is what a caller uses before mapping a page. Passing the result down as a MapStruct
     * {@code @Context} keeps the query count off the row count, which is the shape
     * {@code MaterialStockLookup} established and {@code MapperDatabaseAccessTest} enforces.
     *
     * @param userIds The stamped ids about to be mapped; duplicates and nulls are ignored.
     * @return A lookup over their names, empty when no ids were given.
     */
    @Transactional(readOnly = true)
    public UserNameLookup namesFor(Collection<Long> userIds) {
        Set<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return UserNameLookup.none();
        }
        return UserNameLookup.of(userRepository.findDisplayNamesByIdIn(ids));
    }

    private static Set<Long> distinctIds(Collection<Long> userIds) {
        if (userIds == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        userIds.stream().filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }
}
