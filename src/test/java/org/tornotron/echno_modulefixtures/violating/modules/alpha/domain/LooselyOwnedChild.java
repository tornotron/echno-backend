package org.tornotron.echno_modulefixtures.violating.modules.alpha.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * Points at a tenant-scoped parent through an optional association, so a row could be saved with
 * no parent and therefore no organization. Not ownership.
 */
@Entity
public class LooselyOwnedChild {

    @Id
    private Long id;

    @ManyToOne
    private AlphaRecord record;
}
