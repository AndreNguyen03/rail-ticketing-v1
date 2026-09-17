package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/observability"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/service"
)

// heartbeatInterval: mỗi chu kỳ AwaitAdmission chờ tối đa ngần này rồi gửi 1
// dòng comment SSE (": heartbeat") nếu chưa admitted — giữ connection sống
// qua load balancer/proxy hay đóng connection idle, và để phát hiện client
// đã đóng tab (ctx.Err() != nil) trong khoảng thời gian hợp lý thay vì chờ vô hạn.
const heartbeatInterval = 15 * time.Second

type QueueHandler struct {
	svc     *service.QueueService
	metrics *observability.Metrics
}

func NewQueueHandler(svc *service.QueueService, metrics *observability.Metrics) *QueueHandler {
	return &QueueHandler{svc: svc, metrics: metrics}
}

func (h *QueueHandler) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /queue/{resource}/join", h.join)
	mux.HandleFunc("GET /queue/{resource}/tickets/{ticketId}", h.status)
	mux.HandleFunc("GET /queue/{resource}/length", h.length)
	// KHÔNG đặt dưới prefix /queue/ — "/queue/tickets/{id}/verify" ĐỤNG pattern
	// "/queue/{resource}/tickets/{ticketId}" ở trên (Go ServeMux từ chối đăng ký,
	// panic lúc start: {resource} là wildcard, khớp được cả chữ "tickets" literal,
	// nên "/queue/tickets/tickets/verify" mơ hồ giữa 2 pattern). Tách hẳn prefix
	// /tickets/ ở top-level cho rõ ràng, không mơ hồ.
	mux.HandleFunc("GET /tickets/{ticketId}/verify", h.verify)
	mux.HandleFunc("GET /tickets/{ticketId}/stream", h.stream)
}

func (h *QueueHandler) join(w http.ResponseWriter, r *http.Request) {
	resource := r.PathValue("resource")
	ticket, err := h.svc.Join(resource)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	h.metrics.JoinTotal.Inc()
	writeJSON(w, http.StatusCreated, ticket)
}

func (h *QueueHandler) status(w http.ResponseWriter, r *http.Request) {
	resource := r.PathValue("resource")
	ticketID := r.PathValue("ticketId")
	ticket, err := h.svc.Status(resource, ticketID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, ticket)
}

func (h *QueueHandler) verify(w http.ResponseWriter, r *http.Request) {
	ticketID := r.PathValue("ticketId")
	admitted, err := h.svc.Verify(ticketID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"admitted": admitted})
}

// stream giữ 1 HTTP connection mở (Server-Sent Events) tới khi ticket được
// admit — client KHÔNG cần tự poll /tickets/{id}/verify liên tục. Đây là cơ
// chế thật để giữ hàng trăm nghìn connection rẻ (1 goroutine/connection, xem
// GUIDE.md §5) thay vì REST polling.
func (h *QueueHandler) stream(w http.ResponseWriter, r *http.Request) {
	ticketID := r.PathValue("ticketId")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	ctx := r.Context()
	for {
		if h.svc.AwaitAdmission(ctx, ticketID, heartbeatInterval) {
			fmt.Fprintf(w, "event: admitted\ndata: {\"ticketId\":%q}\n\n", ticketID)
			flusher.Flush()
			return
		}
		if ctx.Err() != nil {
			return // client đã đóng tab/kết nối — dừng, không ghi thêm vào response nữa
		}
		// Hết 1 chu kỳ heartbeat mà chưa admitted — gửi comment giữ connection sống, chờ tiếp.
		fmt.Fprint(w, ": heartbeat\n\n")
		flusher.Flush()
	}
}

func (h *QueueHandler) length(w http.ResponseWriter, r *http.Request) {
	resource := r.PathValue("resource")
	n, err := h.svc.QueueLength(resource)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"length": n})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
