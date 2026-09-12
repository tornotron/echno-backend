package org.tornotron.echno_modulefixtures.violating.modules.alpha.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A module entity with no tenant scope, no owning parent and no declared reason. */
@Entity
public class LeakyEntity {

    @Id
    private Long id;
}
