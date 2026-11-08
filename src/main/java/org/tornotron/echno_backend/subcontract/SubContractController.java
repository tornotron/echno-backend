package org.tornotron.echno_backend.subcontract;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves every endpoint of {@link SubContractControllerWeb} on the tenant API path the mobile client calls
 * (#998). The handlers, and the {@code @PreAuthorize} on each, are inherited rather than
 * copied, so the two paths cannot come to answer differently. When the twin controllers are
 * collapsed (#856), this class goes and SubContractControllerWeb takes both paths.
 */
@RestController
@Validated
@RequestMapping("/api/v1/sub-contracts")
@Tag(
        name = "Subcontracts (Mobile)",
        description = "The tenant API path of the endpoints under /api/v1/sub-contracts/web, for the mobile client. "
                + "Same handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class SubContractController extends SubContractControllerWeb {

    public SubContractController(SubContractService subContractService) {
        super(subContractService);
    }
}
