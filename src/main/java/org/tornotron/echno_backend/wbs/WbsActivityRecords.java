package org.tornotron.echno_backend.wbs;

import java.util.Collection;

/**
 * Something that keeps records against schedule activities and so must outlive them, for example
 * a module's progress inspections. The schedule asks every bean of this type before it deletes an
 * element and refuses the delete when one of them still holds records for it or its descendants.
 *
 * <p>The schedule is core and cannot see a module's tables, so a module that records against
 * activities implements this interface instead of leaving the delete to fail on a foreign key.
 */
public interface WbsActivityRecords {

    /**
     * @param elementIds the element being deleted and every descendant, all in the current tenant
     * @return a short, readable name for the records held against any of them (for example
     *         "progress inspections"), or null when there are none
     */
    String recordsHeldAgainst(Collection<Long> elementIds);
}
