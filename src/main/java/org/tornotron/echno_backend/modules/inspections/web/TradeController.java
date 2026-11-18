package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;

/**
 * Serves every endpoint of {@link TradeControllerWeb} on the tenant API path the mobile client calls
 * (#998). The handlers, and the {@code @PreAuthorize} on each, are inherited rather than
 * copied, so the two paths cannot come to answer differently. When the twin controllers are
 * collapsed (#856), this class goes and TradeControllerWeb takes both paths.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/inspections/trades")
@Tag(
        name = "Inspection trades (Mobile)",
        description = "The tenant API path of the endpoints under /api/v1/inspections/web/trades, for the mobile client. "
                + "Same handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class TradeController extends TradeControllerWeb {

    public TradeController(TradeService service) {
        super(service);
    }
}
