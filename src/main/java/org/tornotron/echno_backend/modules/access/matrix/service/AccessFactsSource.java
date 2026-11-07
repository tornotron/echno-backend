package org.tornotron.echno_backend.modules.access.matrix.service;

import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * Where the matrix engine gets a person's {@link AccessFacts} from: the database, a cache in
 * front of it, or, in tests, plain maps.
 */
public interface AccessFactsSource {

    /** The person's facts; {@link AccessFacts#noAccess()} for anyone who is not an active member. */
    AccessFacts load(AccessSubject subject);
}
