package org.tornotron.echno_backend.common.configuration;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tornotron.echno_backend.common.filter.TenantFilter;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilter() {
        FilterRegistrationBean<TenantFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TenantFilter());
        registrationBean.addUrlPatterns("/*");
        return registrationBean;
    }
}
