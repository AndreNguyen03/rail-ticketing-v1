package service

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/entity"
	"github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/repository"
)

const admittedTokenTTL = 15 * time.Minute // token hết hạn nếu vào cổng mà không kịp đặt vé — khớp TTL hold bên inventory-service

type QueueService struct {
	repo repository.QueueRepository
}

func NewQueueService(repo repository.QueueRepository) *QueueService {
	return &QueueService{repo: repo}
}

// Join thêm 1 người vào hàng đợi của resource (VD 1 trip), trả về ticket
// kèm vị trí hiện tại.
func (s *QueueService) Join(resource string) (entity.Ticket, error) {
	ticketID := uuid.NewString()
	if err := s.repo.Enqueue(resource, ticketID); err != nil {
		return entity.Ticket{}, err
	}
	return s.Status(resource, ticketID)
}

// Status trả về trạng thái hiện tại của 1 ticket: đã admitted (có token
// dùng được) hay còn đang chờ (kèm vị trí).
func (s *QueueService) Status(resource, ticketID string) (entity.Ticket, error) {
	admitted, err := s.repo.IsAdmitted(ticketID)
	if err != nil {
		return entity.Ticket{}, err
	}
	if admitted {
		return entity.Ticket{ID: ticketID, Resource: resource, Status: entity.StatusAdmitted}, nil
	}

	position, found, err := s.repo.Position(resource, ticketID)
	if err != nil {
		return entity.Ticket{}, err
	}
	if !found {
		// Không admitted, cũng không còn trong hàng đợi — token/ticket không tồn tại
		// (chưa từng join, hoặc bị pop nhưng chưa kịp ghi admitted — race cực hẹp, coi như chưa vào).
		return entity.Ticket{ID: ticketID, Resource: resource, Status: entity.StatusWaiting, Position: -1}, nil
	}
	return entity.Ticket{ID: ticketID, Resource: resource, Status: entity.StatusWaiting, Position: position}, nil
}

// ReleaseNext thả `rate` người đầu hàng đợi ra — gọi định kỳ bởi 1 goroutine
// nền (xem cmd/api/main.go). Đây chính là "product-layer backpressure" lever
// nói ở docs/02-architecture.md — chỉnh rate là chỉnh tốc độ tải vào backend.
func (s *QueueService) ReleaseNext(resource string, rate int64) ([]string, error) {
	return s.repo.Release(resource, rate, admittedTokenTTL)
}

func (s *QueueService) QueueLength(resource string) (int64, error) {
	return s.repo.QueueLength(resource)
}

// ActiveResources trả về mọi resource đang có người chờ — dùng bởi vòng lặp
// release nền để không phải hardcode 1 resource cố định.
func (s *QueueService) ActiveResources() ([]string, error) {
	return s.repo.ActiveResources()
}

// Verify cho service khác (api-gateway/booking-service) hỏi "ticketID này có
// token hợp lệ (đã admitted) không" — không cần biết resource, không quan
// tâm vị trí hàng đợi. Đây là điểm tích hợp DUY NHẤT giữa waiting-room và
// phần còn lại của hệ thống — đúng "Knows nothing about tickets" (docs/02).
func (s *QueueService) Verify(ticketID string) (bool, error) {
	return s.repo.IsAdmitted(ticketID)
}

// AwaitAdmission block tới khi ticketID được admit, ctx bị huỷ (client đóng
// SSE connection), hoặc hết `timeout` — dùng bởi handler SSE để giữ 1
// connection mở mà không cần client tự poll.
func (s *QueueService) AwaitAdmission(ctx context.Context, ticketID string, timeout time.Duration) (admitted bool) {
	// Check trước — tránh race "đã admitted trước khi client kịp subscribe"
	// (VD release xảy ra giữa lúc Join() trả về và client mở kết nối SSE).
	if ok, _ := s.repo.IsAdmitted(ticketID); ok {
		return true
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	ch, cleanup := s.repo.SubscribeAdmission(ctx, ticketID)
	defer cleanup()

	select {
	case _, ok := <-ch:
		return ok
	case <-ctx.Done():
		// Timeout hoặc client disconnect — check lại 1 lần cuối phòng race
		// (PUBLISH xảy ra đúng lúc ctx hết hạn, message có thể bị bỏ lỡ).
		admitted, _ = s.repo.IsAdmitted(ticketID)
		return admitted
	}
}
