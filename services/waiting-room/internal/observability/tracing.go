package observability

import (
	"context"
	"log"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// InitTracer nối vào CÙNG Jaeger các service Java đang dùng — cùng
// OTEL_EXPORTER_OTLP_ENDPOINT (VD "http://jaeger:4318"), cùng giao thức OTLP
// HTTP. Trả về hàm shutdown, gọi lúc graceful shutdown để flush nốt span
// đang buffer trước khi process thoát.
func InitTracer(serviceName, otlpEndpoint string) func(context.Context) error {
	// api-gateway forward traceparent header tự động (Spring RestClient +
	// micrometer-tracing observation) — otelhttp ở dưới tự đọc header này để
	// nối đúng trace, không cần code thêm gì ở phía waiting-room.
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{}, propagation.Baggage{},
	))

	host := strings.TrimPrefix(strings.TrimPrefix(otlpEndpoint, "http://"), "https://")
	exporter, err := otlptracehttp.New(context.Background(),
		otlptracehttp.WithEndpoint(host),
		otlptracehttp.WithInsecure(), // Jaeger OTLP nội bộ compose network, không cần TLS
	)
	if err != nil {
		log.Printf("otel exporter init failed, tracing disabled: %v", err)
		return func(context.Context) error { return nil }
	}

	res, _ := resource.New(context.Background(),
		resource.WithAttributes(semconv.ServiceName(serviceName)),
	)

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.AlwaysSample()), // khớp sampling.probability=1.0 bên Java (local/dev)
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown
}
