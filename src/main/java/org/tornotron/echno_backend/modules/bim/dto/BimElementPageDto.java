package org.tornotron.echno_backend.modules.bim.dto;

import java.util.List;

public record BimElementPageDto(
        List<BimElementDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
