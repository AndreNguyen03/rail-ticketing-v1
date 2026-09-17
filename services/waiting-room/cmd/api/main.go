package main

import (
	"context"
	"log"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/config"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/handler"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/observability"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/repository"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/service"
)

func main() {
	cfg := config.Load()

	logger := observability.NewLogger(cfg.ServiceName)
	metrics := observability.NewMetrics()
	shutdownTracer := observability.InitTracer(cfg.ServiceName, cfg.OTLPEndpoint)
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTracer(ctx); err != nil {
			log.Printf("tracer shutdown error: %v", err)
		}
	}()

	redisClient := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr})
	if err := redisClient.Ping(context.Background()).Err(); err != nil {
		log.Fatalf("cannot connect to redis at %s: %v", cfg.RedisAddr, err)
	}

	repo := repository.NewRedisQueueRepository(redisClient)
	svc := service.NewQueueService(repo)
	h := handler.NewQueueHandler(svc, metrics)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /actuator/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, err := w.Write([]byte(`{"status":"UP"}`))
		if err != nil {
			return
		}
	})
	// Path khớp convention `/actuator/prometheus` các service Java (Micrometer)
	// dùng — cùng 1 cách scrape dù service viết bằng gì, xem infra/prometheus/prometheus.yml.
	mux.Handle("GET /actuator/prometheus", promhttp.HandlerFor(metrics.Registry, promhttp.HandlerOpts{}))
	h.Register(mux)

	// otelhttp NGOÀI CÙNG: tạo span (đọc traceparent do api-gateway forward
	// sẵn qua RestClient) TRƯỚC khi Middleware chạy — nhờ vậy Middleware đọc
	// được traceId/spanId từ context để gắn vào log JSON.
	rootHandler := otelhttp.NewHandler(
		observability.Middleware(logger, metrics)(mux),
		cfg.ServiceName,
	)

	// Vòng lặp release nền: đúng "product-layer backpressure" lever
	// (docs/02-architecture.md) — chỉnh RELEASE_RATE/RELEASE_INTERVAL_MS là
	// chỉnh tốc độ tải xuống backend, không cần đổi code. Release cho MỌI
	// resource đang active (nhiều trip cùng lúc), không hardcode 1 resource.
	go releaseLoop(svc, metrics, cfg.ReleaseRate, cfg.ReleaseIntervalMs)

	logger.Info("waiting-room starting",
		"port", cfg.Port, "redis", cfg.RedisAddr,
		"releaseRate", cfg.ReleaseRate, "releaseIntervalMs", cfg.ReleaseIntervalMs)
	log.Fatal(http.ListenAndServe(":"+cfg.Port, rootHandler))
}

// releaseLoop — globalRate là ngân sách CHUNG cho TOÀN HỆ THỐNG mỗi tick
// (đúng nghĩa "release 2,000/s" ở docs/02-architecture.md), không phải rate
// riêng từng resource. Round-robin 1 ticket/resource/vòng cho tới khi hết
// ngân sách hoặc không resource nào còn vé — nhiều trip hot cùng lúc chia
// nhau đúng 1 ngân sách, không cộng dồn thành N× rate như trước.
func releaseLoop(svc *service.QueueService, metrics *observability.Metrics, globalRate int64, intervalMs int) {
	ticker := time.NewTicker(time.Duration(intervalMs) * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		resources, err := svc.ActiveResources()
		if err != nil {
			log.Printf("active resources error: %v", err)
			continue
		}
		metrics.ActiveResources.Set(float64(len(resources)))

		remaining := globalRate
		for remaining > 0 && len(resources) > 0 {
			var stillActive []string
			progressed := false

			for _, resource := range resources {
				if remaining <= 0 {
					stillActive = append(stillActive, resource) // chưa tới lượt, giữ lại cho tick sau
					continue
				}
				released, err := svc.ReleaseNext(resource, 1)
				if err != nil {
					log.Printf("release error for %s: %v", resource, err)
					continue // bỏ khỏi vòng round-robin của tick này, thử lại tick sau
				}
				if len(released) == 0 {
					continue // hết vé đợi ở resource này — tự loại khỏi round-robin, không giữ lại
				}
				metrics.AdmittedTotal.WithLabelValues(resource).Add(float64(len(released)))
				remaining--
				progressed = true
				stillActive = append(stillActive, resource)
			}

			if !progressed {
				break // không ai release được gì cả vòng — dừng, tránh lặp vô hạn
			}
			resources = stillActive
		}

		for _, resource := range resources {
			if n, err := svc.QueueLength(resource); err == nil {
				metrics.QueueLength.WithLabelValues(resource).Set(float64(n))
			}
		}
	}
}
