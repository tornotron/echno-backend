package org.tornotron.echno_backend.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.tornotron.echno_backend.common.context.TenantContext;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {

    @Autowired
    @Qualifier("handlerExceptionResolver")
    private HandlerExceptionResolver resolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader("X-Tenant-ID");

        try {
            if (tenantId == null || tenantId.isEmpty()) {
                throw new TenantIdMissingException("Tenant id 'X-Tenant-ID' header is missing");
            }
            TenantContext.setCurrentTenant(tenantId);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            resolver.resolveException(request, response, null, e);
        } finally {
            TenantContext.clear();
        }
    }
}
