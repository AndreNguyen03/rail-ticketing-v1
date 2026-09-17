package observability

import (
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/trace"
)

const CorrelationIDHeader = "X-Correlation-Id"

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

// normalizePath gộp path có tham số động (ticketID thật) về 1 route mẫu cố
// định — dùng làm label Prometheus. KHÔNG được dùng r.URL.Path thô làm label:
// mỗi ticketID (UUID) là 1 giá trị khác nhau → cardinality nổ vô hạn theo số
// request, Prometheus sẽ OOM về lâu dài.
func normalizePath(path string) string {
	switch {
	case path == "/actuator/health":
		return path
	case path == "/actuator/prometheus":
		return path
	case strings.HasSuffix(path, "/join"):
		return "/queue/{resource}/join"
	case strings.HasSuffix(path, "/verify"):
		return "/tickets/{id}/verify"
	case strings.HasSuffix(path, "/stream"):
		return "/tickets/{id}/stream"
	case strings.HasSuffix(path, "/length"):
		return "/queue/{resource}/length"
	case strings.Contains(path, "/tickets/"):
		return "/queue/{resource}/tickets/{id}"
	default:
		return "other"
	}
}

// Middleware: correlation-id (đọc từ gateway hoặc tự sinh, echo lại, log),
// Prometheus HTTP metrics, gắn traceId/spanId vào log nếu request có span
// (do otelhttp — xem cmd/api/main.go — tạo trước khi tới middleware này).
// Log JSON output field-name khớp convention Java (%logger
// [service,correlationId,traceId,spanId]) để tra log 1 request xuyên cả
// gateway (Java) lẫn waiting-room (Go) bằng đúng 1 correlationId/traceId.
func Middleware(logger *slog.Logger, metrics *Metrics) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()

			cid := r.Header.Get(CorrelationIDHeader)
			if cid == "" {
				cid = uuid.NewString()
			}
			w.Header().Set(CorrelationIDHeader, cid)

			rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(rec, r)

			duration := time.Since(start)
			route := normalizePath(r.URL.Path)

			attrs := []any{
				"correlationId", cid,
				"method", r.Method,
				"path", route,
				"status", rec.status,
				"durationMs", duration.Milliseconds(),
			}
			if span := trace.SpanContextFromContext(r.Context()); span.IsValid() {
				attrs = append(attrs, "traceId", span.TraceID().String(), "spanId", span.SpanID().String())
			}
			logger.Info("http_request", attrs...)

			if metrics != nil {
				metrics.HTTPRequestsTotal.WithLabelValues(r.Method, route, strconv.Itoa(rec.status)).Inc()
				metrics.HTTPRequestDuration.WithLabelValues(r.Method, route).Observe(duration.Seconds())
			}
		})
	}
}
