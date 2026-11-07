package org.tornotron.echno_backend.modules.access.api;

/**
 * Which records a grant covers.
 *
 * <p>A person may hold the same permission with several scopes, through different roles, and they
 * add together: {@link #ALL} covers everything, and the others each add their own records.
 * {@link #RELATED} and {@link #OWN} are separate on purpose. Both mean "records connected to me",
 * but a worker can be given their own leave without their crew's, and a client given their
 * project's bills never gains a record merely because they created it.
 *
 * <p>A grant held through an assignment on one project never reaches past that project, whatever
 * its scope: {@link #ALL} then means every record of that project, and {@link #OWN} the person's
 * own records in it.
 */
public enum AccessScope {

    /** Every record in the organization. */
    ALL,

    /** Records on the projects the person is a member of. */
    ASSIGNED_PROJECTS,

    /**
     * Records linked to the person: assigned to them, or carrying the customer or vendor they
     * represent. Never records they only created.
     */
    RELATED,

    /** The person's own records: ones they created, or about them, such as their own leave. */
    OWN
}
