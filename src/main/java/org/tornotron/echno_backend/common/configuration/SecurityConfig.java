package org.tornotron.echno_backend.common.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.tornotron.echno_backend.common.multitenancy.TenantFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;

    @Value("${API.VERSION}")
    private String backend_version;

    @Value("${keycloak.frontend.web-origin}")
    private List<String> allowedOrigins;

    /**
     * Whether the API docs are served to an unauthenticated caller. Closed unless an
     * environment opts in (SWAGGER_PUBLIC_ACCESS=true), because the OpenAPI document is
     * the entire endpoint surface in one download. Both the fallback here and the value
     * shipped in application.yml are false, so an ingress that reaches this application
     * without passing an edge rule still gets 401 on the docs paths. See issue #569.
     */
    @Value("${springdoc.swagger-ui.public-access:false}")
    private boolean swaggerPublicAccess;

    private static final String[] SWAGGER_PATHS = {"/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RPTExchangeFilter rPTExchangeFilter, TenantFilter tenantFilter) throws Exception{
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(rPTExchangeFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                        // Anything permitted here is also outside tenant isolation, which is not
                        // obvious and is the reason this comment exists. An unauthenticated
                        // request cannot supply an organization id, so TenantFilter declares it
                        // unscoped with the reason "Unauthenticated request to <path>". That
                        // declaration is correct for registration, and it is also what switches
                        // the fail-closed load boundary off for the whole request: a
                        // tenant-scoped entity read from a permitAll path is read across every
                        // organization at once and nothing refuses it. See TenantFilter and
                        // issue #547.
                        //
                        // So before adding a path here, check what its handler reaches. If it
                        // touches tenant-scoped data, it does not belong on this list; scope the
                        // read on something the caller proved and put it behind an authenticated
                        // path instead. PublicEndpointTenantExposureTest walks the handler chain
                        // of every endpoint whose @PreAuthorize lets an unauthenticated caller
                        // through, and fails the build on one that reaches a tenant-scoped
                        // repository, an EntityManager query or a JdbcTemplate statement,
                        // whether it reaches it by a direct call, through an interface, or
                        // across a published event into a synchronous listener. What it cannot
                        // see is a call dispatched by a value rather than by a type: a lambda
                        // handed across a boundary, reflection, a proxy resolved by name. That
                        // limit is stated on the test itself.
                        //
                        // /actuator is the exception, and only because TenantFilter skips it
                        // outright rather than declaring anything: an entity load from there is
                        // still denied at the load boundary.
                        .authorizeHttpRequests(auth -> {
                                auth.requestMatchers(HttpMethod.POST, "/api/"+backend_version+"/auth/register").permitAll()
                                .requestMatchers("/actuator/**").permitAll();
                                if (swaggerPublicAccess) {
                                    auth.requestMatchers(SWAGGER_PATHS).permitAll();
                                }
                                auth.anyRequest().authenticated();
                        })
                        .oauth2ResourceServer(oauth2 -> oauth2
                                .jwt(Customizer.withDefaults())
                                .jwt(jwt -> jwt
                                        .jwtAuthenticationConverter(jwtAuthConverter)))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    /**
     * Prevent Spring Boot from auto-registering these filters as servlet filters.
     * They must only run inside the security filter chain (at their configured positions).
     * Without this, OncePerRequestFilter causes them to run before the security chain
     * (where there is no authentication context) and then skip their security chain execution.
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter tenantFilter) {
        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(tenantFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RPTExchangeFilter> rptExchangeFilterRegistration(RPTExchangeFilter rptExchangeFilter) {
        FilterRegistrationBean<RPTExchangeFilter> registration = new FilterRegistrationBean<>(rptExchangeFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept", "X-Organization-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

}
