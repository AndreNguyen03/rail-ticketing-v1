package observability

import "github.com/prometheus/client_golang/prometheus"

// Metrics — đăng ký vào registry riêng (không dùng default global registry
// của client_golang) để test có thể tạo registry sạch, không dính state giữa
// các lần chạy test.
type Metrics struct {
	Registry *prometheus.Registry

	HTTPRequestsTotal   *prometheus.CounterVec
	HTTPRequestDuration *prometheus.HistogramVec

	QueueLength     *prometheus.GaugeVec // theo resource — cardinality bounded bởi số trip active
	ActiveResources prometheus.Gauge
	AdmittedTotal   *prometheus.CounterVec // theo resource
	JoinTotal       prometheus.Counter
}

func NewMetrics() *Metrics {
	reg := prometheus.NewRegistry()

	m := &Metrics{
		Registry: reg,
		HTTPRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "waitingroom_http_requests_total",
			Help: "Tổng số HTTP request, theo method/path/status",
		}, []string{"method", "path", "status"}),
		HTTPRequestDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "waitingroom_http_request_duration_seconds",
			Help:    "Thời gian xử lý HTTP request",
			Buckets: prometheus.DefBuckets,
		}, []string{"method", "path"}),
		QueueLength: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "waitingroom_queue_length",
			Help: "Số người đang chờ, theo resource",
		}, []string{"resource"}),
		ActiveResources: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "waitingroom_active_resources",
			Help: "Số resource (trip) đang có người chờ",
		}),
		AdmittedTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "waitingroom_admitted_total",
			Help: "Tổng số ticket đã được release, theo resource",
		}, []string{"resource"}),
		JoinTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "waitingroom_join_total",
			Help: "Tổng số lượt join hàng đợi",
		}),
	}

	reg.MustRegister(
		m.HTTPRequestsTotal, m.HTTPRequestDuration,
		m.QueueLength, m.ActiveResources, m.AdmittedTotal, m.JoinTotal,
	)
	return m
}
