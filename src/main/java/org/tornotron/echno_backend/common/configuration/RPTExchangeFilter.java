package org.tornotron.echno_backend.common.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class RPTExchangeFilter extends OncePerRequestFilter {

    private final KeycloakAuthorizationService authorizationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String requestUri = request.getRequestURI();

        if(authHeader != null && authHeader.startsWith("Bearer ")) {
            log.debug("Processing request to {} with Bearer token", requestUri);
            String accessToken = authHeader.substring(7);

            try {
                String rptToken = authorizationService.exchangeForRPT(accessToken);
                log.debug("Successfully obtained RPT token for request to {}", requestUri);

                HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("Authorization".equalsIgnoreCase(name)) {
                            return "Bearer " + rptToken;
                        } else {
                            return super.getHeader(name);
                        }
                    }
                };

                filterChain.doFilter(wrappedRequest, response);
            } catch (Exception e) {
                log.error("Failed to exchange token for RPT on request to {}: {}", requestUri, e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Failed to obtain authorization token");
                return;
            }
        } else {
            log.debug("No Bearer token found for request to {}, continuing without RPT exchange", requestUri);
            filterChain.doFilter(request, response);
        }

    }
}
