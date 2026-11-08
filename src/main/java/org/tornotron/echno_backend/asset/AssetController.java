package org.tornotron.echno_backend.asset;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves every endpoint of {@link AssetControllerWeb} on the tenant API path the mobile client calls
 * (#998). The handlers, and the {@code @PreAuthorize} on each, are inherited rather than
 * copied, so the two paths cannot come to answer differently. When the twin controllers are
 * collapsed (#856), this class goes and AssetControllerWeb takes both paths.
 */
@RestController
@Validated
@RequestMapping("/api/v1/assets")
@Tag(
        name = "Assets (Mobile)",
        description = "The tenant API path of the endpoints under /api/v1/assets/web, for the mobile client. "
                + "Same handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class AssetController extends AssetControllerWeb {

    public AssetController(AssetService assetService) {
        super(assetService);
    }
}
