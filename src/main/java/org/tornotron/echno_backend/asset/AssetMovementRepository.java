package org.tornotron.echno_backend.asset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Reads and appends to the asset movement ledger.
 *
 * <p>This deliberately extends {@link Repository} rather than {@code JpaRepository}. Spring
 * Data only implements the methods a repository actually declares, so the ledger has no
 * {@code delete}, {@code deleteAll} or {@code saveAll}-over-existing surface for anything to
 * call by accident. Append-only is a property of the type here, not a convention someone has
 * to remember; {@link AssetMovement} is {@code @Immutable} on top of that, so an entry cannot
 * be edited through a loaded instance either.
 */
public interface AssetMovementRepository extends Repository<AssetMovement, Long> {

    /** Appends an entry. The only write this repository offers. */
    AssetMovement save(AssetMovement movement);

    /** One entry by id, refusing to cross tenants. */
    Optional<AssetMovement> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * The ledger for one asset, newest first. Serves both the history screen and the placement
     * spans read off it.
     *
     * <p>Newest first rather than oldest on purpose: a capped read of the oldest entries would
     * mark whichever entry the cap stopped at as the placement the asset is still in, which is a
     * plain untruth about where the machine is. The page also carries the whole ledger's size, so
     * a shortened read can say that it was shortened.
     */
    Page<AssetMovement> findByAsset_IdAndOrganization_IdOrderByMovedAtDescIdDesc(
            Long assetId, Long organizationId, Pageable pageable);

    /**
     * The latest entry on one asset's ledger, which is the one the asset's placement columns
     * cache. Used to refuse an entry dated before it, so appended order and chronological order
     * stay the same thing.
     */
    Optional<AssetMovement> findFirstByAsset_IdAndOrganization_IdOrderByMovedAtDescIdDesc(
            Long assetId, Long organizationId);

    /** Whether an asset has any recorded history, which is what makes it undeletable. */
    boolean existsByAsset_IdAndOrganization_Id(Long assetId, Long organizationId);

    /** How many entries an asset carries, so a refusal to delete it can say. */
    long countByAsset_IdAndOrganization_Id(Long assetId, Long organizationId);
}
