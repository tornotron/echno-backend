package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;

/**
 * Serves every endpoint of {@link ChecklistTemplateControllerWeb} on the tenant API path the mobile client calls
 * (#998). The handlers, and the {@code @PreAuthorize} on each, are inherited rather than
 * copied, so the two paths cannot come to answer differently. When the twin controllers are
 * collapsed (#856), this class goes and ChecklistTemplateControllerWeb takes both paths.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@Validated
@RequestMapping("/api/v1/checklist-templates")
@Tag(
        name = "Checklist templates (Mobile)",
        description = "The tenant API path of the endpoints under /api/v1/checklist-templates/web, for the mobile client. "
                + "Same handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class ChecklistTemplateController extends ChecklistTemplateControllerWeb {

    public ChecklistTemplateController(ChecklistTemplateService service) {
        super(service);
    }
}
