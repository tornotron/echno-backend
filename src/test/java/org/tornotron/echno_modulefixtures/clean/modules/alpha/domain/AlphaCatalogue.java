package org.tornotron.echno_modulefixtures.clean.modules.alpha.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;

/** Shared seed data, declared as such. */
@Entity
@GlobalReferenceData("seeded catalogue every organization reads")
public class AlphaCatalogue {

    @Id
    private Long id;
}
