package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A project's schedule in one read: every WBS element as a flat row ordered by wbsCode, with its dates, milestone flag, responsible party and delay, and every dependency link.")
public record WbsScheduleDto(
        @Schema(description = "The elements, ordered by wbsCode; children is null on each row.")
        List<WbsElementDto> activities,
        @Schema(description = "The links between activities.")
        List<WbsDependencyDto> dependencies
) {}
