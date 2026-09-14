package vn.railticketing.gateway.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

@Component
@Order(1)
public class CorrelationIdFilter implements Filter {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        String correlationId = httpReq.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        httpRes.setHeader(HEADER, correlationId);

        // Wrap the request so the gateway forwards X-Correlation-Id downstream.
        // HttpServletRequest headers are immutable — wrapping is the only way to inject one.
        HttpServletRequestWrapper wrapped = getHttpServletRequestWrapper(correlationId, httpReq);

        try {
            chain.doFilter(wrapped, response);
        } finally {
            MDC.clear();
        }
    }

    private static @NonNull HttpServletRequestWrapper getHttpServletRequestWrapper(String correlationId, HttpServletRequest httpReq) {
        final String finalId = correlationId;
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(httpReq) {
            @Override
            public String getHeader(String name) {
                if (HEADER.equalsIgnoreCase(name)) return finalId;
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (HEADER.equalsIgnoreCase(name)) {
                    return Collections.enumeration(List.of(finalId));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                List<String> names = Collections.list(super.getHeaderNames());
                if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                    names.add(HEADER);
                }
                return Collections.enumeration(names);
            }
        };
        return wrapped;
    }
}
