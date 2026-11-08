package org.tornotron.echno_backend.modules.documentingestion.gate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionSwitch;

/**
 * Refuses every request to the module with 403 {@code MODULE_DISABLED} unless the switch is on, the
 * organization is on the allow-list, and the registry says it is enabled for that organization.
 *
 * <p>An interceptor and not an aspect, so that it runs before the request body is bound. An aspect
 * on the controller runs after argument resolution, where a malformed body would answer 400 from a
 * module that is meant to be off. It also runs before {@code @PreAuthorize}, so a person never
 * learns what they could do in a module the organization does not have. The platform gate in
 * {@code 2026-10-31-module-tiers-and-independence.md} takes the same place and uses the same code.
 *
 * <p>The registry comes through a provider because web slice tests build this interceptor with no
 * registry in the context; it is only asked once the switch and the allow-list have said yes.
 */
@Component
public class DocumentIngestionGateInterceptor implements HandlerInterceptor {

    private final DocumentIngestionSwitch operatorSwitch;
    private final ObjectProvider<ModuleRegistry> registry;

    public DocumentIngestionGateInterceptor(Environment environment, ObjectProvider<ModuleRegistry> registry) {
        this.operatorSwitch = new DocumentIngestionSwitch(environment);
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && AnnotatedElementUtils.hasAnnotation(method.getBeanType(), DocumentIngestionGate.class)) {
            check(TenantContext.getCurrentOrgId());
        }
        return true;
    }

    void check(Long organizationId) {
        if (!operatorSwitch.isOn()) {
            throw refused(ModuleDisabledException.Reason.SWITCHED_OFF);
        }
        if (organizationId == null) {
            throw refused(ModuleDisabledException.Reason.NO_ORGANIZATION);
        }
        if (!operatorSwitch.allows(organizationId)) {
            throw refused(ModuleDisabledException.Reason.NOT_ALLOWED);
        }
        if (!registry.getObject().isEnabledForOrg(DocumentIngestionModule.ID, organizationId)) {
            throw refused(ModuleDisabledException.Reason.NOT_ENTITLED);
        }
    }

    private static ModuleDisabledException refused(ModuleDisabledException.Reason reason) {
        return new ModuleDisabledException(DocumentIngestionModule.ID, reason);
    }
}
