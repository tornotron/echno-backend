package org.tornotron.echno_backend.siteTransfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.asset.Asset;
import org.tornotron.echno_backend.asset.AssetMovement;
import org.tornotron.echno_backend.asset.AssetMovementRepository;
import org.tornotron.echno_backend.asset.AssetMovementType;
import org.tornotron.echno_backend.asset.AssetRepository;
import org.tornotron.echno_backend.asset.AssetService;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a site transfer does to the assets it carries.
 *
 * <p>An asset line rides the same document as a material line and goes through the same states,
 * but it moves a machine rather than a balance, so where a material line writes a stock movement
 * an asset line writes an entry on the asset's movement ledger through
 * {@link AssetService#moveOnSiteTransfer}. That is the one place an asset's placement changes,
 * so the asset's project and location cannot come to disagree with its history.
 *
 * <p>The lifecycle, which follows the material legs exactly:
 * <ul>
 *   <li><b>Within one project</b> the transfer is complete the moment it is written, so the asset
 *       moves to the receiving storage location at creation.</li>
 *   <li><b>Between two projects</b> the asset is in transit from creation until somebody at the
 *       receiving site records it arriving. The line carries {@code assetInTransit} while it is,
 *       and the asset stays where the ledger last put it, the sending site, until the receipt
 *       writes the entry that moves it.</li>
 *   <li><b>Cancelling</b> an in-transit transfer clears the flag. The asset never left the
 *       ledger's sending site, so there is nothing to write back.</li>
 *   <li><b>Reversing</b> a transfer puts each asset back where the transfer found it: an asset
 *       still in transit has its flag cleared, and one the transfer moved gets a correction of the
 *       entry the transfer wrote.</li>
 * </ul>
 *
 * <p>All of it runs inside the transaction of the transfer operation that called it, so an asset
 * moves exactly when the document commits and not otherwise. None of it is deferred to an event.
 */
@Component
public class SiteTransferAssetLines {

    private final AssetRepository assetRepository;
    private final AssetService assetService;
    private final AssetMovementRepository assetMovementRepository;
    private final SiteTransferItemRepository siteTransferItemRepository;

    public SiteTransferAssetLines(AssetRepository assetRepository,
                                  AssetService assetService,
                                  AssetMovementRepository assetMovementRepository,
                                  SiteTransferItemRepository siteTransferItemRepository) {
        this.assetRepository = assetRepository;
        this.assetService = assetService;
        this.assetMovementRepository = assetMovementRepository;
        this.siteTransferItemRepository = siteTransferItemRepository;
    }

    /**
     * The assets a transfer from one project and storage location can send.
     *
     * <p>Matches the rule {@link #requireSendable} applies, which is the balance-row rule the
     * material lines follow: a named location means assets at that location, and no location means
     * assets on the project that sit at no location.
     */
    public Page<Asset> sendableFrom(Long projectId, Long storageLocationId) {
        Long orgId = TenantContext.getCurrentOrgId();
        PageRequest cap = UnpagedResultCap.firstPage();
        return storageLocationId != null
                ? assetRepository.findTransferableAtLocation(orgId, projectId, storageLocationId, cap)
                : assetRepository.findTransferableWithoutLocation(orgId, projectId, cap);
    }

    /**
     * Resolves and checks the asset on each asset line of a create payload, taking each asset
     * under a write lock.
     *
     * @return The asset for each asset line, in payload order, keyed by the line's position.
     * @throws ResourceNotFoundException if an asset is not in this organization.
     * @throws InvalidRequestException if a line is malformed, an asset appears twice, an asset is
     *     not at the sending side, or it is already in transit on another transfer.
     */
    public List<Asset> resolveForCreation(List<SiteTransferItemDto> assetLines, Project sendingProject,
                                          StorageLocation sendingLocation) {
        Long orgId = TenantContext.getCurrentOrgId();
        Set<Long> seen = new HashSet<>();
        List<Asset> assets = new ArrayList<>(assetLines.size());
        for (SiteTransferItemDto line : assetLines) {
            if (line.getAssetId() == null) {
                throw new InvalidRequestException("An ASSET line must name the asset it moves in assetId.");
            }
            if (line.getMaterialId() != null) {
                throw new InvalidRequestException("An ASSET line moves one asset and cannot also name a "
                        + "material. Send the material as a MATERIAL line of its own.");
            }
            if (line.getSentQuantity() == null || line.getSentQuantity() != 1) {
                throw new InvalidRequestException("An ASSET line always sends one unit, because an asset is "
                        + "one machine. Send sentQuantity 1, and add a line for each asset.");
            }
            if (!seen.add(line.getAssetId())) {
                throw new InvalidRequestException("Asset with ID " + line.getAssetId() + " appears on more "
                        + "than one line of this transfer. An asset can only be sent once.");
            }
            Asset asset = assetRepository.lockByIdAndOrganizationId(line.getAssetId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Asset with ID " + line.getAssetId() + " was not found in this organization"));
            requireSendable(asset, sendingProject, sendingLocation);
            assets.add(asset);
        }
        return assets;
    }

    /**
     * Refuses an asset that is not at the sending side, or that is already on its way somewhere.
     *
     * <p>The sending side is the transfer's sending project and storage location, with no location
     * meaning the project's assets that sit at no location. That is the rule a material line's
     * stock check follows, and it means the transfer knows exactly where to put the asset back if
     * it is cancelled or reversed.
     */
    private void requireSendable(Asset asset, Project sendingProject, StorageLocation sendingLocation) {
        siteTransferItemRepository
                .findFirstByAsset_IdAndAssetInTransitTrueAndOrganization_Id(asset.getId(), TenantContext.getCurrentOrgId())
                .ifPresent(open -> {
                    throw new InvalidRequestException("Asset " + AssetService.describe(asset) + " is already in "
                            + "transit on site transfer " + open.getSiteTransfer().getTransferNumber()
                            + ". An asset can be on one open transfer at a time; receive or cancel that "
                            + "transfer first.");
                });

        Long assetProject = asset.getAssignedProject() != null ? asset.getAssignedProject().getId() : null;
        Long assetLocation = asset.getLocation() != null ? asset.getLocation().getId() : null;
        Long sendingLocationId = sendingLocation != null ? sendingLocation.getId() : null;
        if (Objects.equals(assetProject, sendingProject.getId()) && Objects.equals(assetLocation, sendingLocationId)) {
            return;
        }
        String sendingSide = sendingProject.getProjectName()
                + (sendingLocation != null ? ", " + sendingLocation.getLocationName() : ", at no storage location");
        throw new InvalidRequestException("Asset " + AssetService.describe(asset) + " is at "
                + whereItIs(asset) + ", not at " + sendingSide + " where this transfer sends from. "
                + "A transfer can only send an asset from where the asset register says it is.");
    }

    /**
     * Carries each asset line from creation to where it stands once the transfer is written.
     *
     * <p>Within one project the asset moves to the receiving storage location now; between two it
     * is marked in transit and stays at the sending site on the ledger until it is received.
     */
    public void dispatch(SiteTransfer transfer, List<SiteTransferItem> assetLines, boolean crossesProjects) {
        for (SiteTransferItem line : assetLines) {
            if (crossesProjects) {
                line.setAssetInTransit(true);
                continue;
            }
            line.setAssetInTransit(false);
            assetService.moveOnSiteTransfer(line.getAsset(), transfer.getReceivingProject(),
                    transfer.getReceivingStorageLocation(), transfer.getIssueDate(),
                    "Moved on site transfer " + transfer.getTransferNumber() + " from "
                            + describeStore(transfer.getSendingProject(), transfer.getSendingStorageLocation())
                            + " to "
                            + describeStore(transfer.getReceivingProject(), transfer.getReceivingStorageLocation()),
                    transfer.getTransferNumber(), transfer.getId(), null);
        }
    }

    /**
     * Moves each asset a receipt recorded as arriving to the receiving site, and clears its
     * in-transit flag.
     *
     * @param receivedLines The asset lines this receipt took from nothing received to one.
     */
    public void receive(SiteTransfer transfer, List<SiteTransferItem> receivedLines, LocalDateTime receivedOn,
                        String receivedByName) {
        for (SiteTransferItem line : receivedLines) {
            Asset asset = lock(line.getAsset());
            line.setAssetInTransit(false);
            assetService.moveOnSiteTransfer(asset, transfer.getReceivingProject(),
                    transfer.getReceivingStorageLocation(), receivedOn,
                    "Received on site transfer " + transfer.getTransferNumber() + " from "
                            + describeStore(transfer.getSendingProject(), transfer.getSendingStorageLocation())
                            + (receivedByName != null ? ", received by " + receivedByName : ""),
                    transfer.getTransferNumber(), transfer.getId(), null);
        }
    }

    /**
     * Releases every asset on a transfer abandoned in transit. The ledger never moved them off the
     * sending site, so clearing the flag is the whole of putting them back.
     */
    public void cancel(List<SiteTransferItem> lines) {
        for (SiteTransferItem line : lines) {
            if (line.isAssetLine()) {
                line.setAssetInTransit(false);
            }
        }
    }

    /**
     * What stands in the way of reversing a transfer's asset lines, or nothing.
     *
     * <p>An asset the transfer moved can be put back only while it is still where the transfer left
     * it. If it has moved on since, or is on its way somewhere on another transfer, putting it back
     * would undo a movement that happened after this one and that the transfer knows nothing about.
     */
    public Optional<String> reversalBlocker(SiteTransfer transfer) {
        for (SiteTransferItem line : assetLinesOf(transfer)) {
            if (line.isAssetInTransit() || !wasMoved(transfer, line)) {
                continue;
            }
            Asset asset = line.getAsset();
            Optional<SiteTransferItem> open = siteTransferItemRepository
                    .findFirstByAsset_IdAndAssetInTransitTrueAndOrganization_Id(asset.getId(), TenantContext.getCurrentOrgId());
            if (open.isPresent()) {
                return Optional.of("Site transfer " + transfer.getTransferNumber() + " moved asset "
                        + AssetService.describe(asset) + ", which is now in transit on site transfer "
                        + open.get().getSiteTransfer().getTransferNumber() + ". Receive or cancel that "
                        + "transfer before reversing this one.");
            }
            if (!isAt(asset, transfer.getReceivingProject(), transfer.getReceivingStorageLocation())) {
                return Optional.of("Site transfer " + transfer.getTransferNumber() + " moved asset "
                        + AssetService.describe(asset) + " to "
                        + describeStore(transfer.getReceivingProject(), transfer.getReceivingStorageLocation())
                        + ", and it has moved on since to " + whereItIs(asset) + ". The transfer cannot be "
                        + "reversed while the asset is elsewhere; move it with a transfer of its own instead.");
            }
        }
        return Optional.empty();
    }

    /**
     * Puts each asset on a transfer being reversed back where the transfer found it.
     *
     * <p>Called after {@link #reversalBlocker} has come back empty under the reversal's lock. An
     * asset still in transit has its flag cleared. One the transfer moved gets a
     * {@link AssetMovementType#CORRECTION} naming the entry the transfer wrote, the ledger's own way
     * of saying an earlier entry no longer stands.
     *
     * @return How many assets were moved back on the ledger.
     */
    public int reverse(SiteTransfer transfer, Long reversalId, String reason) {
        int movedBack = 0;
        for (SiteTransferItem line : assetLinesOf(transfer)) {
            if (line.isAssetInTransit()) {
                line.setAssetInTransit(false);
                siteTransferItemRepository.save(line);
                continue;
            }
            Optional<AssetMovement> entry = transferEntry(transfer, line);
            if (entry.isEmpty()) {
                continue;
            }
            Asset asset = lock(line.getAsset());
            assetService.moveOnSiteTransfer(asset, transfer.getSendingProject(),
                    transfer.getSendingStorageLocation(), LocalDateTime.now(),
                    "Site transfer " + transfer.getTransferNumber() + " reversed under request #" + reversalId
                            + (reason != null && !reason.isBlank() ? ": " + reason.trim() : ""),
                    transfer.getTransferNumber(), transfer.getId(), entry.get().getId());
            movedBack++;
        }
        return movedBack;
    }

    private List<SiteTransferItem> assetLinesOf(SiteTransfer transfer) {
        return siteTransferItemRepository.findBySiteTransferId(transfer.getId()).stream()
                .filter(SiteTransferItem::isAssetLine)
                .toList();
    }

    private boolean wasMoved(SiteTransfer transfer, SiteTransferItem line) {
        return transferEntry(transfer, line).isPresent();
    }

    /** The ledger entry this transfer wrote when it moved the line's asset, if it moved it. */
    private Optional<AssetMovement> transferEntry(SiteTransfer transfer, SiteTransferItem line) {
        return assetMovementRepository
                .findFirstByAsset_IdAndSiteTransferIdAndMovementTypeAndOrganization_IdOrderByIdDesc(
                        line.getAsset().getId(), transfer.getId(), AssetMovementType.TRANSFER,
                        TenantContext.getCurrentOrgId());
    }

    private Asset lock(Asset asset) {
        return assetRepository.lockByIdAndOrganizationId(asset.getId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Asset with ID " + asset.getId() + " was not found in this organization"));
    }

    private static boolean isAt(Asset asset, Project project, StorageLocation location) {
        Long assetProject = asset.getAssignedProject() != null ? asset.getAssignedProject().getId() : null;
        Long assetLocation = asset.getLocation() != null ? asset.getLocation().getId() : null;
        return Objects.equals(assetProject, project != null ? project.getId() : null)
                && Objects.equals(assetLocation, location != null ? location.getId() : null);
    }

    private static String whereItIs(Asset asset) {
        String project = asset.getAssignedProject() != null
                ? asset.getAssignedProject().getProjectName() : "no project";
        String location = asset.getLocation() != null
                ? asset.getLocation().getLocationName() : "no storage location";
        return project + ", " + location;
    }

    private static String describeStore(Project project, StorageLocation location) {
        String name = project != null ? project.getProjectName() : "no project";
        return location != null ? name + " (" + location.getLocationName() + ")" : name;
    }
}
