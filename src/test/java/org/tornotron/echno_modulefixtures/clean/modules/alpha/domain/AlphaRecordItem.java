package org.tornotron.echno_modulefixtures.clean.modules.alpha.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** An owned child: no organization column of its own, reached only through its parent. */
@Entity
public class AlphaRecordItem {

    @Id
    private Long id;

    @ManyToOne(optional = false)
    private AlphaRecord record;
}
