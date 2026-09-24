package vn.railticketing.gateway.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Runs after Spring Security (Order -100) has validated the JWT and populated
// SecurityContext. Extracts the subject claim and injects it as X-Identity so
// downstream services receive a verified identity without knowing about Keycloak.
// On public endpoints (unauthenticated requests) the filter is a no-op.
@Component
@Order(0)
public class IdentityPropagationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(IdentityPropagationFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            log.debug("propagating identity sub={}", sub);
            HttpServletRequest wrapped = new IdentityHeaderRequestWrapper(
                    (HttpServletRequest) request, sub);
            chain.doFilter(wrapped, response);
        } else {
            // Anonymous or unauthenticated (public endpoint) — forward as-is.
            chain.doFilter(request, response);
        }
    }
}
