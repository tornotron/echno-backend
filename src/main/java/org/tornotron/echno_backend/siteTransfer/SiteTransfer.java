package org.tornotron.echno_backend.siteTransfer;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class SiteTransfer implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_number", nullable = false, unique = true)
    private String transferNumber;

    @Column(name = "issue_date")
    private LocalDateTime issueDate;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee sendingPerson;

    @Column(name = "receiving_site")
    private String receivingSite;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SiteTransferStatus status;

    @OneToMany(mappedBy = "siteTransfer")
    private List<SiteTransferItem> items;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
