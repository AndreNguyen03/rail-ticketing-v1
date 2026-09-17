package service

import (
	"context"
	"sync"
	"testing"
	"time"
)

// fakeRepo implement repository.QueueRepository trong RAM — test SERVICE
// (điều phối/business logic), không phải test Redis lần nữa (đã có
// redis_queue_repository_test.go riêng, dùng testcontainers).
type fakeRepo struct {
	mu       sync.Mutex
	queues   map[string][]string // resource -> ticketID theo thứ tự FIFO
	admitted map[string]bool
	subs     map[string][]chan struct{}
}

func newFakeRepo() *fakeRepo {
	return &fakeRepo{
		queues:   map[string][]string{},
		admitted: map[string]bool{},
		subs:     map[string][]chan struct{}{},
	}
}

func (f *fakeRepo) Enqueue(resource, ticketID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, id := range f.queues[resource] {
		if id == ticketID {
			return nil // NX semantics — đã có, không thêm lại
		}
	}
	f.queues[resource] = append(f.queues[resource], ticketID)
	return nil
}

func (f *fakeRepo) Position(resource, ticketID string) (int64, bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for i, id := range f.queues[resource] {
		if id == ticketID {
			return int64(i), true, nil
		}
	}
	return 0, false, nil
}

func (f *fakeRepo) Release(resource string, count int64, _ time.Duration) ([]string, error) {
	f.mu.Lock()
	q := f.queues[resource]
	n := int(count)
	if n > len(q) {
		n = len(q)
	}
	released := append([]string(nil), q[:n]...)
	f.queues[resource] = q[n:]
	for _, id := range released {
		f.admitted[id] = true
	}
	subsCopy := map[string][]chan struct{}{}
	for _, id := range released {
		subsCopy[id] = f.subs[id]
		delete(f.subs, id)
	}
	f.mu.Unlock()

	for _, chs := range subsCopy {
		for _, ch := range chs {
			ch <- struct{}{}
		}
	}
	return released, nil
}

func (f *fakeRepo) ActiveResources() ([]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []string
	for resource, q := range f.queues {
		if len(q) > 0 {
			out = append(out, resource)
		}
	}
	return out, nil
}

func (f *fakeRepo) SubscribeAdmission(ctx context.Context, ticketID string) (<-chan struct{}, func()) {
	f.mu.Lock()
	ch := make(chan struct{}, 1)
	f.subs[ticketID] = append(f.subs[ticketID], ch)
	f.mu.Unlock()
	return ch, func() {}
}

func (f *fakeRepo) IsAdmitted(ticketID string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.admitted[ticketID], nil
}

func (f *fakeRepo) QueueLength(resource string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return int64(len(f.queues[resource])), nil
}

// ── Tests ────────────────────────────────────────────────────────────────────

func TestJoin_ReturnsWaitingWithPosition(t *testing.T) {
	svc := NewQueueService(newFakeRepo())

	ticket, err := svc.Join("trip-1")
	if err != nil {
		t.Fatalf("join: %v", err)
	}
	if ticket.Status != "WAITING" || ticket.Position != 0 {
		t.Errorf("expected WAITING/position 0, got %+v", ticket)
	}
}

func TestStatus_ReflectsAdmissionAfterRelease(t *testing.T) {
	repo := newFakeRepo()
	svc := NewQueueService(repo)

	ticket, _ := svc.Join("trip-1")
	if _, err := svc.ReleaseNext("trip-1", 1); err != nil {
		t.Fatalf("release: %v", err)
	}

	status, err := svc.Status("trip-1", ticket.ID)
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if status.Status != "ADMITTED" {
		t.Errorf("expected ADMITTED after release, got %s", status.Status)
	}
}

func TestAwaitAdmission_ReturnsImmediatelyIfAlreadyAdmitted(t *testing.T) {
	repo := newFakeRepo()
	svc := NewQueueService(repo)

	ticket, _ := svc.Join("trip-1")
	if _, err := svc.ReleaseNext("trip-1", 1); err != nil {
		t.Fatalf("release: %v", err)
	}

	// Race đã nêu trong service.go: release xảy ra TRƯỚC khi subscribe —
	// AwaitAdmission phải check IsAdmitted trước, không được treo tới hết timeout.
	start := time.Now()
	admitted := svc.AwaitAdmission(context.Background(), ticket.ID, 2*time.Second)
	if !admitted {
		t.Fatal("expected already-admitted ticket to return true immediately")
	}
	if elapsed := time.Since(start); elapsed > 200*time.Millisecond {
		t.Errorf("expected near-instant return (race check), took %v", elapsed)
	}
}

func TestAwaitAdmission_UnblocksWhenReleasedConcurrently(t *testing.T) {
	repo := newFakeRepo()
	svc := NewQueueService(repo)

	ticket, _ := svc.Join("trip-1")

	done := make(chan bool, 1)
	go func() {
		done <- svc.AwaitAdmission(context.Background(), ticket.ID, 3*time.Second)
	}()

	time.Sleep(100 * time.Millisecond) // để goroutine trên kịp subscribe trước khi release
	if _, err := svc.ReleaseNext("trip-1", 1); err != nil {
		t.Fatalf("release: %v", err)
	}

	select {
	case admitted := <-done:
		if !admitted {
			t.Error("expected admitted=true after concurrent release")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("AwaitAdmission did not unblock after release")
	}
}

func TestAwaitAdmission_TimesOutIfNeverReleased(t *testing.T) {
	repo := newFakeRepo()
	svc := NewQueueService(repo)
	ticket, _ := svc.Join("trip-1")

	start := time.Now()
	admitted := svc.AwaitAdmission(context.Background(), ticket.ID, 300*time.Millisecond)
	if admitted {
		t.Error("expected admitted=false when never released")
	}
	if elapsed := time.Since(start); elapsed < 300*time.Millisecond {
		t.Errorf("expected to wait out the full timeout, returned after %v", elapsed)
	}
}
