package vn.railticketing.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import vn.railticketing.gateway.client.WaitingRoomClient;

import java.io.IOException;
import java.util.List;

// Chặn request vào CONTENTION PLANE (holds/bookings) nếu client chưa được
// waiting-room admit — đúng "product-layer backpressure" lever (docs/02).
// Không áp cho catalog plane (/api/v1/trips) — đọc lịch tàu không cần xếp hàng.
@Component
@Order(2) // sau CorrelationIdFilter (Order 1) — request bị từ chối vẫn có correlation id để trace
public class QueueAdmissionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionFilter.class);
    private static final String TICKET_HEADER = "X-Queue-Ticket";

    private final WaitingRoomClient waitingRoomClient;
    private final boolean enabled;
    private final List<String> protectedPrefixes;

    public QueueAdmissionFilter(
            WaitingRoomClient waitingRoomClient,
            @Value("${queue.enforcement.enabled:false}") boolean enabled,
            @Value("${queue.enforcement.protected-prefixes:/api/v1/holds,/api/v1/bookings}") List<String> protectedPrefixes) {
        this.waitingRoomClient = waitingRoomClient;
        this.enabled = enabled;
        this.protectedPrefixes = protectedPrefixes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        if (!enabled || !isProtected(httpReq.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String ticketId = httpReq.getHeader(TICKET_HEADER);
        if (ticketId == null || ticketId.isBlank()) {
            reject(httpRes, "missing " + TICKET_HEADER + " header - join the waiting room first");
            return;
        }

        try {
            if (waitingRoomClient.verify(ticketId).admitted()) {
                chain.doFilter(request, response);
            } else {
                reject(httpRes, "ticket not admitted yet - still waiting in queue");
            }
        } catch (RestClientException e) {
            // waiting-room không tới được/timeout — fail-open: backpressure chỉ là
            // 1 lever bảo vệ, không được biến thành single point of failure của
            // toàn gateway. Log để điều tra, không chặn request vì lỗi này.
            log.warn("waiting-room verify failed, fail-open (letting request through): {}", e.getMessage());
            chain.doFilter(request, response);
        }
    }

    private boolean isProtected(String uri) {
        return protectedPrefixes.stream().anyMatch(uri::startsWith);
    }

    private void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(429); // Too Many Requests — không có hằng số SC_* cho 429 trong HttpServletResponse
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
