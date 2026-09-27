package org.tornotron.echno_backend.modules.workprogress.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.workprogress.domain.DelayReason;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressInspection;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressInspectionEvidence;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressOutcome;
import org.tornotron.echno_backend.modules.workprogress.dto.ProgressInspectionDto;
import org.tornotron.echno_backend.modules.workprogress.dto.RecordProgressInspectionRequest;
import org.tornotron.echno_backend.modules.workprogress.repository.ProgressInspectionRepository;
import org.tornotron.echno_backend.modules.workprogress.time.WorkProgressClock;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.spatial.SpatialNodeRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.WbsDelay;
import org.tornotron.echno_backend.wbs.WbsElement;
import org.tornotron.echno_backend.wbs.WbsElementRepository;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

/**
 * Progress inspections as the API sees them. Every read is scoped to the caller's organization so
 * a foreign id reads as absent; every write stamps the current tenant and user.
 *
 * <p>The rules, in one place. The activity is a leaf of a project in the caller's organization and
 * is neither completed nor cancelled. The inspection date is not in the sites' future. The outcome
 * fixes the percent: DONE is 100, PARTIAL is strictly between 0 and 100, NOT_DONE is 0 and only
 * while no progress has been recorded; a milestone is DONE or NOT_DONE. DONE needs an actual
 * finish, the others refuse one. A delay (worked out against the planned finish, never moving it)
 * needs a reason, and OTHER needs notes. The record is then applied to the activity in the same
 * transaction; nothing else in the schedule changes.
 */
@Service
public class ProgressInspectionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProgressInspectionRepository inspections;
    private final WbsElementRepository elements;
    private final WbsElementService schedule;
    private final SpatialNodeRepository spatialNodes;
    private final EmployeeRepository employees;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final Clock clock;

    public ProgressInspectionService(ProgressInspectionRepository inspections,
                                     WbsElementRepository elements,
                                     WbsElementService schedule,
                                     SpatialNodeRepository spatialNodes,
                                     EmployeeRepository employees,
                                     AttachmentService attachmentService,
                                     AttachmentMapper attachmentMapper,
                                     TenantEntityHelper tenantEntityHelper,
                                     UserContextService userContextService,
                                     @WorkProgressClock Clock clock) {
        this.inspections = inspections;
        this.elements = elements;
        this.schedule = schedule;
        this.spatialNodes = spatialNodes;
        this.employees = employees;
        this.attachmentService = attachmentService;
        this.attachmentMapper = attachmentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.clock = clock;
    }

    @Transactional
    public ProgressInspectionDto record(RecordProgressInspectionRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        WbsElement activity = elements.findByIdAndOrganization_Id(req.wbsElementId(), org.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found: " + req.wbsElementId()));
        String code = activity.getWbsCode();
        if (!Boolean.TRUE.equals(activity.getIsLeaf())) {
            throw new InvalidRequestException("Activity " + code
                    + " has sub-activities; its progress is rolled up from them, so inspect those instead");
        }
        if (activity.getStatus() == WbsStatus.COMPLETED || activity.getStatus() == WbsStatus.CANCELLED) {
            throw new InvalidRequestException("Activity " + code + " is " + activity.getStatus().name().toLowerCase()
                    + " and takes no further progress inspections");
        }
        Long projectId = activity.getProject().getId();
        LocalDate today = LocalDate.now(clock);
        LocalDate inspected = req.inspectionDate();
        if (inspected.isAfter(today)) {
            throw new InvalidRequestException("The inspection date " + inspected + " is in the future");
        }
        // Each record is applied to the activity as its current state, so one dated before the
        // latest would overwrite a newer finding with an older one.
        inspections.findLatestInspectionDate(activity.getId(), org.getId()).ifPresent(latest -> {
            if (inspected.isBefore(latest)) {
                throw new InvalidRequestException("Activity " + code + " already has an inspection dated " + latest
                        + "; a new one cannot be dated earlier");
            }
        });
        if (req.spatialNodeId() != null) {
            spatialNodes.findByIdAndProjectId(req.spatialNodeId(), projectId)
                    .orElseThrow(() -> new InvalidRequestException(
                            "Location " + req.spatialNodeId() + " is not part of this activity's project"));
        }

        ProgressOutcome outcome = req.outcome();
        boolean milestone = Boolean.TRUE.equals(activity.getIsMilestone());
        BigDecimal percent = requirePercent(outcome, req.percentComplete(), milestone, activity, code);
        LocalDate actualStart = requireActualStart(outcome, req.actualStartDate(), activity, inspected, code);
        LocalDate actualFinish = requireActualFinish(outcome, req.actualFinishDate(), actualStart, inspected);
        LocalDate forecast = req.forecastFinishDate();
        if (forecast != null) {
            if (outcome == ProgressOutcome.DONE) {
                throw new InvalidRequestException("A finished activity has an actual finish, not a forecast");
            }
            if (forecast.isBefore(inspected)) {
                throw new InvalidRequestException("The forecast finish " + forecast
                        + " is before the inspection date " + inspected);
            }
        }

        Integer delayDays = WbsDelay.delayDays(activity.getEndDate(), actualFinish, forecast, inspected);
        if (delayDays != null && delayDays > 0 && req.delayReason() == null) {
            throw new InvalidRequestException("Activity " + code + " is " + delayDays
                    + " day(s) behind its planned finish " + activity.getEndDate() + "; record the reason for the delay");
        }
        String notes = req.delayNotes() == null || req.delayNotes().isBlank() ? null : req.delayNotes().trim();
        if (req.delayReason() == DelayReason.OTHER && notes == null) {
            throw new InvalidRequestException("Describe the delay when the reason is OTHER");
        }

        ProgressInspection record = new ProgressInspection();
        record.setOrganization(org);
        record.setProjectId(projectId);
        record.setWbsElementId(activity.getId());
        record.setInspectionDate(inspected);
        record.setOutcome(outcome);
        record.setPercentComplete(percent);
        record.setActualStartDate(actualStart);
        record.setActualFinishDate(actualFinish);
        record.setForecastFinishDate(forecast);
        record.setPlannedFinishDate(activity.getEndDate());
        record.setDelayDays(delayDays);
        record.setDelayReason(req.delayReason());
        record.setDelayNotes(notes);
        record.setSpatialNodeId(req.spatialNodeId());
        record.setRemarks(req.remarks() == null || req.remarks().isBlank() ? null : req.remarks().trim());
        Long userId = userContextService.getCurrentUserId();
        record.setRecordedBy(userId);
        if (userId != null) {
            employees.findByUserIdAndOrganizationId(userId, org.getId())
                    .ifPresent(e -> record.setInspectorEmployeeId(e.getId()));
        }
        ProgressInspection saved = inspections.save(record);

        WbsStatus status = switch (outcome) {
            case DONE -> WbsStatus.COMPLETED;
            case PARTIAL -> activity.getStatus() == WbsStatus.ON_HOLD ? null : WbsStatus.IN_PROGRESS;
            case NOT_DONE -> null;
        };
        schedule.applyInspectedProgress(activity.getId(), percent.doubleValue(), actualStart, actualFinish,
                forecast, status);
        return toDtos(List.of(saved)).get(0);
    }

    @Transactional(readOnly = true)
    public Page<ProgressInspectionDto> list(Long projectId, Long wbsElementId, int page, int size) {
        Long orgId = tenantEntityHelper.resolveCurrentOrganization().getId();
        Page<ProgressInspection> rows = inspections.findPage(orgId, projectId, wbsElementId, PageRequest.of(page, size));
        return new PageImpl<>(toDtos(rows.getContent()), rows.getPageable(), rows.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProgressInspectionDto get(UUID id) {
        return toDtos(List.of(require(id))).get(0);
    }

    // Evidence rides on the platform's attachment store through the direct-to-storage path:
    // presign, upload from the device, register. The keys are checked back against the record's
    // own folder so a registration cannot claim an object presigned for something else. Evidence
    // can be added after the record is saved; the record itself does not change.

    @Transactional(readOnly = true)
    public List<AttachmentDto> listEvidence(UUID id) {
        require(id);
        return attachmentService.getAttachments(ProgressInspectionEvidence.ENTITY_TYPE, id);
    }

    @Transactional(readOnly = true)
    public List<PresignedUpload> presignEvidence(UUID id, List<UploadRequest> uploads) {
        require(id);
        return attachmentService.presignUploads(uploads, ProgressInspectionEvidence.ownerOf(id),
                ProgressInspectionEvidence.folderFor(id));
    }

    @Transactional
    public List<AttachmentDto> registerEvidence(UUID id, List<RegisterUploadRequest> uploads) {
        require(id);
        String folder = ProgressInspectionEvidence.folderFor(id);
        String prefix = folder + "/";
        for (RegisterUploadRequest upload : uploads == null ? List.<RegisterUploadRequest>of() : uploads) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this inspection");
            }
        }
        return attachmentService.registerUploads(uploads, ProgressInspectionEvidence.ownerOf(id), folder)
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
    }

    // ---------------------------------------------------------------- rules

    private ProgressInspection require(UUID id) {
        Long orgId = tenantEntityHelper.resolveCurrentOrganization().getId();
        return inspections.findScoped(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Progress inspection not found: " + id));
    }

    private static BigDecimal requirePercent(ProgressOutcome outcome, BigDecimal given, boolean milestone,
                                             WbsElement activity, String code) {
        switch (outcome) {
            case DONE -> {
                if (given != null && given.compareTo(HUNDRED) != 0) {
                    throw new InvalidRequestException("A finished activity is 100 percent complete");
                }
                return HUNDRED;
            }
            case PARTIAL -> {
                if (milestone) {
                    throw new InvalidRequestException("Activity " + code
                            + " is a milestone: it is either reached (DONE) or not (NOT_DONE)");
                }
                if (given == null || given.signum() <= 0 || given.compareTo(HUNDRED) >= 0) {
                    throw new InvalidRequestException(
                            "A partly done activity needs a percent complete above 0 and below 100");
                }
                return given;
            }
            case NOT_DONE -> {
                if (given != null && given.signum() != 0) {
                    throw new InvalidRequestException("An activity with no work done is 0 percent complete");
                }
                if (activity.getProgress() != null && activity.getProgress() > 0) {
                    throw new InvalidRequestException("Activity " + code + " already shows " + activity.getProgress()
                            + " percent progress; record it as PARTIAL with the current percent");
                }
                return BigDecimal.ZERO;
            }
            default -> throw new IllegalStateException("Unknown outcome " + outcome);
        }
    }

    private static LocalDate requireActualStart(ProgressOutcome outcome, LocalDate given, WbsElement activity,
                                                LocalDate inspected, String code) {
        LocalDate existing = activity.getActualStartDate();
        if (outcome == ProgressOutcome.NOT_DONE) {
            if (given != null) {
                throw new InvalidRequestException("An activity with no work done has no actual start");
            }
            return null;
        }
        if (existing != null && given != null && !existing.equals(given)) {
            throw new InvalidRequestException("Activity " + code + " already started on " + existing
                    + "; correct the activity itself if that date is wrong");
        }
        LocalDate start = existing != null ? existing : given;
        if (start == null) {
            throw new InvalidRequestException("Give the date work started on activity " + code);
        }
        if (start.isAfter(inspected)) {
            throw new InvalidRequestException("The actual start " + start + " is after the inspection date " + inspected);
        }
        return start;
    }

    private static LocalDate requireActualFinish(ProgressOutcome outcome, LocalDate given, LocalDate start,
                                                 LocalDate inspected) {
        if (outcome != ProgressOutcome.DONE) {
            if (given != null) {
                throw new InvalidRequestException("Only a finished activity has an actual finish date");
            }
            return null;
        }
        if (given == null) {
            throw new InvalidRequestException("Give the date the activity finished");
        }
        if (given.isAfter(inspected)) {
            throw new InvalidRequestException("The actual finish " + given + " is after the inspection date " + inspected);
        }
        if (start != null && given.isBefore(start)) {
            throw new InvalidRequestException("The actual finish " + given + " is before the actual start " + start);
        }
        return given;
    }

    private List<ProgressInspectionDto> toDtos(List<ProgressInspection> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long orgId = rows.get(0).getOrganization().getId();
        Set<Long> activityIds = new HashSet<>();
        Set<Long> employeeIds = new HashSet<>();
        for (ProgressInspection row : rows) {
            activityIds.add(row.getWbsElementId());
            if (row.getInspectorEmployeeId() != null) {
                employeeIds.add(row.getInspectorEmployeeId());
            }
        }
        Map<Long, WbsElement> activities = new HashMap<>();
        for (Long id : activityIds) {
            elements.findByIdAndOrganization_Id(id, orgId).ifPresent(e -> activities.put(id, e));
        }
        Map<Long, String> names = new HashMap<>();
        if (!employeeIds.isEmpty()) {
            for (Employee employee : employees.findAllByIdInAndOrganizationId(employeeIds, orgId)) {
                names.put(employee.getId(), employee.getEmployeeName());
            }
        }
        return rows.stream().map(row -> {
            WbsElement activity = activities.get(row.getWbsElementId());
            return new ProgressInspectionDto(row.getId(), row.getProjectId(), row.getWbsElementId(),
                    activity != null ? activity.getWbsCode() : null,
                    activity != null ? activity.getTitle() : null,
                    row.getInspectionDate(), row.getOutcome(), row.getPercentComplete(),
                    row.getActualStartDate(), row.getActualFinishDate(), row.getForecastFinishDate(),
                    row.getPlannedFinishDate(), row.getDelayDays(), row.getDelayReason(), row.getDelayNotes(),
                    row.getSpatialNodeId(), row.getRemarks(), row.getInspectorEmployeeId(),
                    names.get(row.getInspectorEmployeeId()), row.getCreatedAt());
        }).toList();
    }
}
