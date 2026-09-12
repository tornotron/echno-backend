package org.tornotron.echno_backend.modules.inspections.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;

/**
 * The inspections module's public face for element type codes, so the core spatial hierarchy
 * can check a node's {@code elementType} slug against the organization's list without
 * reaching into the module. Core injects it through an {@code ObjectProvider}: absent, no
 * validation happens, which keeps the two deployable in either order.
 */
@Component
@RequiredArgsConstructor
public class ElementTypeValidator {

    private final ElementTypeService elementTypeService;

    /**
     * Refuses a code the organization has no active element type for.
     *
     * @throws InvalidRequestException when the code is unknown or retired
     */
    public void requireActive(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (!elementTypeService.isActiveCode(code)) {
            throw new InvalidRequestException("Unknown element type: " + code
                    + ". Define it under the organization's element types first, or reactivate it.");
        }
    }
}
