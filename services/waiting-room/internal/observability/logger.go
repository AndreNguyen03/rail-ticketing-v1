package observability

import (
	"log/slog"
	"os"
)

// NewLogger — JSON structured log, field "service" khớp convention log Java
// (%logger [service,correlationId,traceId,spanId] bên application.yml các
// service khác). correlationId được gắn theo từng request qua middleware,
// không gắn cố định ở đây.
func NewLogger(serviceName string) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	return slog.New(handler).With("service", serviceName)
}
