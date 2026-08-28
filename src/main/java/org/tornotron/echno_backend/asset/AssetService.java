package org.tornotron.echno_backend.asset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.asset.dto.AssetCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementDto;
import org.tornotron.echno_backend.asset.dto.AssetPlacementSpanDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.asset.mapper.AssetMovementMapper;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Service
public class AssetService {

    /**
     * Reason recorded when an asset's placement changes through the create or the update
     * endpoint rather than through the movements endpoint. It says where the entry came from
     * instead of inventing a reason for a movement nobody explained, and the caller can supply
     * a real one in {@code movementReason}.
     */
    static final String EDIT_REASON = "Recorded from an asset edit";

    /** Reason recorded on the opening entry an asset gets when it is first registered. */
    static final String REGISTRATION_REASON = "Asset registered";

    /**
     * Most ledger entries the placement history reads. An asset that has moved more than this
     * is far past the point where a screen would show every stretch; the movements endpoint is
     * paginated and answers that case.
     */
    static final int MAX_HISTORY_ENTRIES = 500;

    private final AssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final VendorRepository vendorRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;
    private final AssetMovementRepository assetMovementRepository;
    private final AssetMovementMapper assetMovementMapper;
    private final UserContextService userContextService;
    private final AttachmentService attachmentService;

    public AssetService(AssetRepository assetRepository,
                        AssetMapper assetMapper,
                        TenantEntityHelper tenantEntityHelper,
                        VendorRepository vendorRepository,
                        StorageLocationRepository storageLocationRepository,
                        ProjectRepository projectRepository,
                        AssetMovementRepository assetMovementRepository,
                        AssetMovementMapper assetMovementMapper,
                        UserContextService userContextService,
                        AttachmentService attachmentService) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.vendorRepository = vendorRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.assetMovementRepository = assetMovementRepository;
        this.assetMovementMapper = assetMovementMapper;
        this.userContextService = userContextService;
        this.attachmentService = attachmentService;
    }

    /**
     * Registers an asset and opens its movement ledger.
     *
     * <p>The placement the payload names is not written straight onto the asset. It goes
     * through {@link #applyPlacement}, which appends the opening {@code REGISTRATION} entry and
     * sets the asset's placement columns from it, so an asset has a history from the moment it
     * exists rather than from the first time somebody happens to move it.
     */
    @Transactional
    public AssetDto createAsset(AssetCreationDto creationDto) {
        Asset asset = new Asset();
        applyFields(asset, creationDto);
        asset.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        Asset saved = assetRepository.save(asset);

        applyPlacement(saved,
                resolveProject(creationDto.getAssignedProjectId()),
                resolveLocation(creationDto.getLocationId()),
                creationDto.getAssignedToId(),
                creationDto.getAssignedTo(),
                reasonOrDefault(creationDto.getMovementReason(), REGISTRATION_REASON),
                creationDto.getMovedAt(),
                null, null, null,
                true);

        return assetMapper.toDto(assetRepository.save(saved));
    }

    @Transactional(readOnly = true)
    public AssetDto getAssetById(Long id) {
        return assetMapper.toDto(requireAsset(id));
    }


    @Transactional(readOnly = true)
    public Page<AssetDto> getAllAssets(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return assetRepository.findAll(pageable)
                .map(asset -> assetMapper.toDto(asset));
    }

    /**
     * Updates an asset, recording any change of placement as a movement.
     *
     * <p>The asset form sends the whole object, so an edit can move the asset as a side effect.
     * Rather than refuse that and break the screen, the change goes through the same
     * {@link #applyPlacement} the movements endpoint uses, so the ledger cannot fall behind the
     * asset. The entry says it came from an edit unless the payload gives a real reason.
     */
    @Transactional
    public AssetDto updateAsset(Long id, AssetCreationDto creationDto) {
        Asset asset = requireAsset(id);
        applyFields(asset, creationDto);

        applyPlacement(asset,
                resolveProject(creationDto.getAssignedProjectId()),
                resolveLocation(creationDto.getLocationId()),
                creationDto.getAssignedToId(),
                creationDto.getAssignedTo(),
                reasonOrDefault(creationDto.getMovementReason(), EDIT_REASON),
                creationDto.getMovedAt(),
                null, null, null,
                false);

        return assetMapper.toDto(assetRepository.save(asset));
    }

    /**
     * Deletes an asset that has no recorded history.
     *
     * <p>An asset that has moved cannot be deleted. Its ledger is the record of where a machine
     * was, and deleting the asset would take that with it; the same reasoning that stops a
     * posted stock adjustment being deleted rather than corrected. Retire the asset by setting
     * its status instead.
     *
     * @throws InvalidRequestException if the asset carries ledger entries.
     */
    @Transactional
    public void deleteAsset(Long id) {
        Asset asset = requireAsset(id);
        long movements = assetMovementRepository
                .countByAsset_IdAndOrganization_Id(id, TenantContext.getCurrentOrgId());
        if (movements > 0) {
            throw new InvalidRequestException("Asset with ID " + id + " has " + movements
                    + " recorded movements and cannot be deleted, because deleting it would take the"
                    + " record of where the machine has been with it. Set its status to retired instead.");
        }
        assetRepository.delete(asset);
    }

    /**
     * Moves an asset and appends the entry that explains the move.
     *
     * <p>This is the only path that exists purely to change where an asset is, and the reason is
     * mandatory on it. Setting {@code correctsMovementId} records the entry as a
     * {@code CORRECTION} of an earlier one: the wrong entry stays where it is and this one
     * supersedes it, in the same shape as a posted stock adjustment, which is corrected by
     * raising a further adjustment rather than by editing the one that was wrong.
     *
     * @throws ResourceNotFoundException if the asset, project, location or corrected entry names nothing in this organization.
     * @throws InvalidRequestException if the movement is dated in the future, or leaves the asset exactly where it already was without being a correction.
     */
    @Transactional
    public AssetMovementDto recordMovement(Long assetId, AssetMovementCreationDto dto) {
        Asset asset = requireAsset(assetId);

        Long corrects = dto.getCorrectsMovementId();
        if (corrects != null) {
            AssetMovement corrected = assetMovementRepository
                    .findByIdAndOrganization_Id(corrects, TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Asset movement with ID " + corrects
                            + " was not found in this organization"));
            if (!Objects.equals(corrected.getAsset().getId(), assetId)) {
                throw new InvalidRequestException("Asset movement with ID " + corrects
                        + " belongs to a different asset, so it cannot be corrected on asset with ID " + assetId);
            }
        }

        AssetMovement movement = applyPlacement(asset,
                resolveProject(dto.getToProjectId()),
                resolveLocation(dto.getToLocationId()),
                dto.getToAssignedToId(),
                dto.getToAssignedTo(),
                dto.getReason(),
                dto.getMovedAt(),
                dto.getNotes(),
                dto.getReferenceNumber(),
                corrects,
                false);

        if (movement == null) {
            throw new InvalidRequestException("Asset with ID " + assetId
                    + " is already on that project, at that location and with that custodian, so"
                    + " there is no movement to record. Send correctsMovementId to restate an"
                    + " earlier entry instead.");
        }

        assetRepository.save(asset);
        return assetMovementMapper.toDto(movement);
    }

    /** One page of an asset's ledger, newest entry first. */
    @Transactional(readOnly = true)
    public Page<AssetMovementDto> getMovements(Long assetId, int pageNo, int pageSize) {
        requireAsset(assetId);
        return assetMovementRepository
                .findByAsset_IdAndOrganization_IdOrderByMovedAtDescIdDesc(
                        assetId, TenantContext.getCurrentOrgId(), PageRequest.of(pageNo, pageSize))
                .map(assetMovementMapper::toDto);
    }

    /**
     * How long the asset spent in each place, worked out from consecutive ledger entries.
     *
     * <p>Each entry opens a placement that runs until the next entry, or until now for the one
     * the asset is in. That is what answers "14 days at Central Yard, 45 days at Silver Oak"
     * without storing a second copy of anything: the durations are read off the ledger, so they
     * cannot drift from it.
     *
     * <p>A {@code CORRECTION} closes the placement before it exactly as any other entry does.
     * The superseded stretch stays visible with the length it was recorded as having, which is
     * the point of an append-only ledger: what was believed at the time is still legible.
     */
    @Transactional(readOnly = true)
    public List<AssetPlacementSpanDto> getPlacementHistory(Long assetId) {
        requireAsset(assetId);
        List<AssetMovement> movements = assetMovementRepository
                .findByAsset_IdAndOrganization_IdOrderByMovedAtAscIdAsc(
                        assetId, TenantContext.getCurrentOrgId(),
                        PageRequest.of(0, MAX_HISTORY_ENTRIES));

        LocalDateTime now = LocalDateTime.now();
        List<AssetPlacementSpanDto> spans = new ArrayList<>(movements.size());
        for (int i = 0; i < movements.size(); i++) {
            AssetMovement movement = movements.get(i);
            boolean last = i == movements.size() - 1;
            LocalDateTime end = last ? null : movements.get(i + 1).getMovedAt();

            AssetPlacementSpanDto span = new AssetPlacementSpanDto();
            span.setMovementId(movement.getId());
            span.setProjectId(movement.getToProject() != null ? movement.getToProject().getId() : null);
            span.setProjectName(movement.getToProjectName());
            span.setLocationId(movement.getToLocation() != null ? movement.getToLocation().getId() : null);
            span.setLocationName(movement.getToLocationName());
            span.setAssignedToId(movement.getToAssignedToId());
            span.setAssignedTo(movement.getToAssignedTo());
            span.setFrom(movement.getMovedAt());
            span.setTo(end);
            span.setCurrent(last);
            span.setReason(movement.getReason());
            span.setDays(Duration.between(movement.getMovedAt(), end != null ? end : now).toDays());
            spans.add(span);
        }
        return spans;
    }

    /**
     * The documents filed against an asset: purchase invoice, warranty, insurance, registration,
     * certifications and service records, each with its expiry where one was recorded.
     */
    @Transactional(readOnly = true)
    public List<AttachmentDto> getDocuments(Long assetId) {
        requireAsset(assetId);
        return attachmentService.getAttachments(AssetDocuments.ENTITY_TYPE, assetId);
    }

    /**
     * Asset documents whose expiry falls on or before the given number of days from today,
     * soonest first. Documents already expired are included, because an insurance policy that
     * lapsed last week is the case this list exists to surface.
     */
    @Transactional(readOnly = true)
    public List<AttachmentDto> getExpiringDocuments(int withinDays) {
        if (withinDays < 0) {
            throw new InvalidRequestException("withinDays must not be negative");
        }
        return attachmentService.getExpiringDocuments(AssetDocuments.ENTITY_TYPE,
                LocalDate.now().plusDays(withinDays));
    }

    /**
     * The one place an asset's placement changes.
     *
     * <p>Compares the placement asked for against the one the asset holds. If nothing moved, it
     * writes nothing and returns null, so an ordinary edit of an asset's serial number does not
     * litter the ledger with entries that record no movement. If something moved, it appends the
     * entry <em>and</em> sets the asset's four placement columns from that entry's "to" side, in
     * that order and in one transaction. Those columns are therefore a cache of the ledger and
     * cannot disagree with it.
     *
     * <p>The names of the project and the location are snapshotted onto the entry, because a
     * project that is renamed or deleted must not rewrite what the history says.
     *
     * @param openingEntry Whether this is the asset's first entry, which is recorded as a REGISTRATION even when it moves nothing.
     * @return The appended entry, or null when nothing moved.
     */
    private AssetMovement applyPlacement(Asset asset,
                                         Project toProject,
                                         StorageLocation toLocation,
                                         Long toAssignedToId,
                                         String toAssignedTo,
                                         String reason,
                                         LocalDateTime movedAt,
                                         String notes,
                                         String referenceNumber,
                                         Long correctsMovementId,
                                         boolean openingEntry) {
        Project fromProject = asset.getAssignedProject();
        StorageLocation fromLocation = asset.getLocation();
        Long fromAssignedToId = asset.getAssignedToId();
        String fromAssignedTo = asset.getAssignedTo();

        boolean projectChanged = !Objects.equals(idOf(fromProject), idOf(toProject));
        boolean locationChanged = !Objects.equals(idOf(fromLocation), idOf(toLocation));
        boolean custodianChanged = !Objects.equals(fromAssignedToId, toAssignedToId)
                || !Objects.equals(fromAssignedTo, toAssignedTo);

        boolean correction = correctsMovementId != null;
        if (!projectChanged && !locationChanged && !custodianChanged && !openingEntry && !correction) {
            return null;
        }

        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("The movement of asset with ID " + asset.getId()
                    + " has no reason. Every movement must say why it happened, the same way every"
                    + " stock movement does; an entry with none is what makes a ledger unexplainable.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime when = movedAt != null ? movedAt : now;
        if (when.isAfter(now)) {
            throw new InvalidRequestException("A movement of asset with ID " + asset.getId()
                    + " cannot be dated " + when + ", which is in the future. A ledger records what"
                    + " has happened, not what is planned.");
        }

        AssetMovement movement = new AssetMovement();
        movement.setAsset(asset);
        movement.setOrganization(asset.getOrganization());
        movement.setMovementType(movementType(openingEntry, correction, projectChanged, locationChanged));
        movement.setFromProject(fromProject);
        movement.setFromProjectName(projectName(fromProject, asset.getLegacyAssignedProject()));
        movement.setToProject(toProject);
        movement.setToProjectName(projectName(toProject, null));
        movement.setFromLocation(fromLocation);
        movement.setFromLocationName(fromLocation != null ? fromLocation.getLocationName() : null);
        movement.setToLocation(toLocation);
        movement.setToLocationName(toLocation != null ? toLocation.getLocationName() : null);
        movement.setFromAssignedToId(fromAssignedToId);
        movement.setFromAssignedTo(fromAssignedTo);
        movement.setToAssignedToId(toAssignedToId);
        movement.setToAssignedTo(toAssignedTo);
        movement.setMovedAt(when);
        movement.setMovedBy(userContextService.getCurrentUserId());
        movement.setReason(reason);
        movement.setNotes(notes);
        movement.setReferenceNumber(referenceNumber);
        movement.setCorrectsMovementId(correctsMovementId);

        AssetMovement appended = assetMovementRepository.save(movement);

        asset.setAssignedProject(toProject);
        asset.setLocation(toLocation);
        asset.setAssignedToId(toAssignedToId);
        asset.setAssignedTo(toAssignedTo);

        return appended;
    }

    /** The kind of entry the change amounts to. A correction is always a correction. */
    private AssetMovementType movementType(boolean openingEntry, boolean correction,
                                           boolean projectChanged, boolean locationChanged) {
        if (correction) {
            return AssetMovementType.CORRECTION;
        }
        if (openingEntry) {
            return AssetMovementType.REGISTRATION;
        }
        return projectChanged || locationChanged
                ? AssetMovementType.TRANSFER
                : AssetMovementType.ASSIGNMENT;
    }

    private Long idOf(Project project) {
        return project != null ? project.getId() : null;
    }

    private Long idOf(StorageLocation location) {
        return location != null ? location.getId() : null;
    }

    /** The project's name for the snapshot, falling back to text the asset carried before the reference. */
    private String projectName(Project project, String fallback) {
        return project != null ? project.getProjectName() : fallback;
    }

    private String reasonOrDefault(String supplied, String fallback) {
        return supplied != null && !supplied.isBlank() ? supplied : fallback;
    }

    private Asset requireAsset(Long id) {
        return assetRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset with ID " + id + " was not found in this organization"));
    }

    private Project resolveProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));
    }

    private StorageLocation resolveLocation(Long locationId) {
        if (locationId == null) {
            return null;
        }
        return storageLocationRepository.findByIdAndOrganization_Id(locationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + locationId + " was not found in this organization"));
    }

    /**
     * Copies the mutable fields from the creation DTO onto the asset, resolving the vendor.
     *
     * <p>Where the asset is and who holds it is deliberately not here: those four columns are
     * set only by {@link #applyPlacement}, alongside the ledger entry that explains them.
     */
    private void applyFields(Asset asset, AssetCreationDto dto) {
        asset.setAssetId(dto.getAssetId());
        asset.setName(dto.getName());
        asset.setDescription(dto.getDescription());
        asset.setType(dto.getType());
        asset.setCategory(dto.getCategory());
        asset.setStatus(dto.getStatus());
        asset.setAssetCondition(dto.getAssetCondition());
        asset.setPurchaseDate(dto.getPurchaseDate());
        asset.setPurchasePrice(dto.getPurchasePrice());
        asset.setCurrentValue(dto.getCurrentValue());
        asset.setDepreciationRate(dto.getDepreciationRate());
        asset.setManufacturer(dto.getManufacturer());
        asset.setModel(dto.getModel());
        asset.setSerialNumber(dto.getSerialNumber());
        asset.setRegistrationNumber(dto.getRegistrationNumber());
        asset.setWarrantyExpiry(dto.getWarrantyExpiry());
        asset.setLastMaintenanceDate(dto.getLastMaintenanceDate());
        asset.setNextMaintenanceDate(dto.getNextMaintenanceDate());
        asset.setInsuranceExpiry(dto.getInsuranceExpiry());
        asset.setMaintenanceSchedule(dto.getMaintenanceSchedule());
        asset.setUsageHours(dto.getUsageHours());
        asset.setMaxUsageHours(dto.getMaxUsageHours());
        asset.setFuelType(dto.getFuelType());
        asset.setInsuranceProvider(dto.getInsuranceProvider());
        asset.setPolicyNumber(dto.getPolicyNumber());
        asset.setNotes(dto.getNotes());

        Vendor vendor = null;
        if (dto.getVendorId() != null) {
            vendor = vendorRepository.findByIdAndOrganization_Id(dto.getVendorId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + dto.getVendorId() + " was not found in this organization"));
        }
        asset.setVendor(vendor);
    }
}
