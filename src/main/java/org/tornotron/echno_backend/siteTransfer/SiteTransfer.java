package org.tornotron.echno_backend.siteTransfer;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Moves stock of one or more materials from a sending project or location to a receiving one.
 *
 * <p>Lists the transferred materials as {@link SiteTransferItem} lines and moves through a
 * status lifecycle. Posting a transfer draws stock down at the sending side and raises it
 * at the receiving side through paired inventory transactions, after the sending stock is
 * checked to be sufficient.
 */
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sending_project_id", nullable = false)
    private Project sendingProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_project_id", nullable = false)
    private Project receivingProject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SiteTransferStatus status;

    @OneToMany(mappedBy = "siteTransfer")
    private List<SiteTransferItem> items;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sending_storage_location_id")
    private StorageLocation sendingStorageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_storage_location_id")
    private StorageLocation receivingStorageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
