package config

import (
	"os"
	"strconv"
)

type Config struct {
	Port              string
	RedisAddr         string
	ReleaseRate       int64 // số ticket release mỗi chu kỳ — "product-layer backpressure" lever (docs/02)
	ReleaseIntervalMs int
	ServiceName       string
	OTLPEndpoint      string
}

func Load() Config {
	return Config{
		Port:              getEnv("SERVER_PORT", "8085"),
		RedisAddr:         getEnv("REDIS_HOST", "localhost") + ":" + getEnv("REDIS_PORT", "6379"),
		ReleaseRate:       getEnvInt64("RELEASE_RATE", 10),
		ReleaseIntervalMs: getEnvInt("RELEASE_INTERVAL_MS", 1000),
		// Cùng tên biến môi trường các service Java đang dùng (OTEL_SERVICE_NAME,
		// OTEL_EXPORTER_OTLP_ENDPOINT) — nối chung 1 Jaeger, không cần biến riêng.
		ServiceName:  getEnv("OTEL_SERVICE_NAME", "waiting-room"),
		OTLPEndpoint: getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt64(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
