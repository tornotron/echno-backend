package org.tornotron.echno_backend.modules.documentingestion.gate;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Mounts the gate on the module's own paths, so no other request passes through it. */
@Configuration(proxyBeanMethods = false)
public class DocumentIngestionGateWebConfig implements WebMvcConfigurer {

    private final DocumentIngestionGateInterceptor gate;

    public DocumentIngestionGateWebConfig(DocumentIngestionGateInterceptor gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate).addPathPatterns("/api/*/document-ingestion/**");
    }
}
