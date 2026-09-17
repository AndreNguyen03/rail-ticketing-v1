package repository

import (
	"context"
	"time"
)

// QueueRepository là interface — service phụ thuộc vào NÓ, không phụ thuộc
// Redis trực tiếp. Domain (service) không biết implementation là Redis hay
// gì khác.
type QueueRepository interface {
	// Enqueue thêm ticketID vào cuối hàng đợi của resource, theo thứ tự thời gian tới.
	Enqueue(resource, ticketID string) error

	// Position trả về vị trí (0-based) của ticketID trong hàng đợi.
	// found=false nếu ticketID không còn trong hàng đợi (đã được release hoặc chưa từng tồn tại).
	Position(resource, ticketID string) (position int64, found bool, err error)

	// Release lấy ra tối đa `count` ticketID đứng đầu hàng đợi (thứ tự vào trước ra trước),
	// đánh dấu admitted (TTL để token hết hạn nếu không dùng), trả về danh sách đã release.
	Release(resource string, count int64, admittedTTL time.Duration) ([]string, error)

	// IsAdmitted kiểm tra ticketID đã được release (có token hợp lệ) hay chưa.
	IsAdmitted(ticketID string) (bool, error)

	// QueueLength trả về số người đang chờ trong hàng đợi của resource.
	QueueLength(resource string) (int64, error)

	// ActiveResources trả về danh sách resource ĐANG có người chờ — để vòng
	// lặp release nền biết cần release cho những resource nào, không phải
	// hardcode 1 resource cố định.
	ActiveResources() ([]string, error)

	// SubscribeAdmission trả về 1 channel nhận đúng 1 tín hiệu khi ticketID
	// được admit — qua Redis Pub/Sub, hoạt động dù ticket được admit bởi 1
	// INSTANCE waiting-room KHÁC (cần cho scale ngang nhiều pod, không chỉ
	// instance đang giữ connection SSE này). Gọi cleanup() khi không cần
	// subscribe nữa (client disconnect/timeout) để không rò rỉ goroutine +
	// kết nối Redis.
	SubscribeAdmission(ctx context.Context, ticketID string) (ch <-chan struct{}, cleanup func())
}
