package vn.railticketing.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Servlet request headers are immutable. This wrapper injects X-Identity with
// the verified subject from the JWT so downstream services receive a trusted value.
class IdentityHeaderRequestWrapper extends HttpServletRequestWrapper {

    private static final String IDENTITY_HEADER = "X-Identity";

    private final Map<String, List<String>> extraHeaders;

    IdentityHeaderRequestWrapper(HttpServletRequest request, String identity) {
        super(request);
        this.extraHeaders = new LinkedHashMap<>();
        this.extraHeaders.put(IDENTITY_HEADER, List.of(identity));
    }

    @Override
    public String getHeader(String name) {
        if (IDENTITY_HEADER.equalsIgnoreCase(name)) {
            return extraHeaders.get(IDENTITY_HEADER).get(0);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (IDENTITY_HEADER.equalsIgnoreCase(name)) {
            return Collections.enumeration(extraHeaders.get(IDENTITY_HEADER));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = Collections.list(super.getHeaderNames());
        if (!names.contains(IDENTITY_HEADER)) {
            names.add(IDENTITY_HEADER);
        }
        return Collections.enumeration(names);
    }
}
