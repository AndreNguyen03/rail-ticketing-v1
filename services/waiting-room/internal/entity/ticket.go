package entity

type TicketStatus string

const (
	StatusWaiting  TicketStatus = "WAITING"
	StatusAdmitted TicketStatus = "ADMITTED"
)

// Ticket là vé xếp hàng — KHÔNG liên quan gì tới vé tàu. waiting-room không
// biết booking/inventory là gì (docs/02-architecture.md: "Knows nothing
// about tickets. It only issues an admission token. Fully decoupled from
// the domain").
type Ticket struct {
	ID       string
	Resource string // hàng đợi theo TỪNG trip (VD "SE1-2026-02-14"), không phải 1 hàng đợi chung toàn hệ thống
	Status   TicketStatus
	Position int64 // ước tính tại thời điểm hỏi — đổi liên tục khi có người phía trước được release
}
