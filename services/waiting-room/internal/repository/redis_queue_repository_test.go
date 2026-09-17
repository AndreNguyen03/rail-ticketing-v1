package repository

import (
	"context"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	tcredis "github.com/testcontainers/testcontainers-go/modules/redis"
)

// newTestRepo dựng 1 Redis THẬT qua testcontainers (đúng convention project —
// Java dùng testcontainers-postgresql, không dùng H2/fake DB). Mỗi test tự
// có container riêng, không share state.
func newTestRepo(t *testing.T) *RedisQueueRepository {
	t.Helper()
	ctx := context.Background()

	container, err := tcredis.Run(ctx, "redis:7-alpine")
	if err != nil {
		t.Fatalf("start redis container: %v", err)
	}
	t.Cleanup(func() {
		if err := container.Terminate(ctx); err != nil {
			t.Logf("terminate redis container: %v", err)
		}
	})

	connStr, err := container.ConnectionString(ctx)
	if err != nil {
		t.Fatalf("get connection string: %v", err)
	}
	opts, err := redis.ParseURL(connStr)
	if err != nil {
		t.Fatalf("parse redis url: %v", err)
	}

	return NewRedisQueueRepository(redis.NewClient(opts))
}

func TestEnqueueAndPosition_FIFOOrder(t *testing.T) {
	repo := newTestRepo(t)

	// 3 ticket join theo thứ tự — Position phải giữ đúng thứ tự vào trước ra trước.
	for _, id := range []string{"t1", "t2", "t3"} {
		if err := repo.Enqueue("trip-1", id); err != nil {
			t.Fatalf("enqueue %s: %v", id, err)
		}
	}

	for i, id := range []string{"t1", "t2", "t3"} {
		pos, found, err := repo.Position("trip-1", id)
		if err != nil {
			t.Fatalf("position %s: %v", id, err)
		}
		if !found {
			t.Fatalf("expected %s to be found in queue", id)
		}
		if pos != int64(i) {
			t.Errorf("%s: expected position %d, got %d", id, i, pos)
		}
	}
}

func TestEnqueue_DuplicateJoinKeepsOriginalPosition(t *testing.T) {
	repo := newTestRepo(t)

	must(t, repo.Enqueue("trip-1", "t1"))
	must(t, repo.Enqueue("trip-1", "t2"))
	// Join lại t1 (VD retry mạng) — KHÔNG được nhảy xuống cuối hàng.
	must(t, repo.Enqueue("trip-1", "t1"))

	pos, found, err := repo.Position("trip-1", "t1")
	if err != nil || !found {
		t.Fatalf("position t1: found=%v err=%v", found, err)
	}
	if pos != 0 {
		t.Errorf("expected t1 to keep position 0 after duplicate join, got %d", pos)
	}
}

func TestRelease_FIFOAndMarksAdmitted(t *testing.T) {
	repo := newTestRepo(t)

	must(t, repo.Enqueue("trip-1", "t1"))
	must(t, repo.Enqueue("trip-1", "t2"))
	must(t, repo.Enqueue("trip-1", "t3"))

	released, err := repo.Release("trip-1", 2, 15*time.Minute)
	if err != nil {
		t.Fatalf("release: %v", err)
	}
	if len(released) != 2 || released[0] != "t1" || released[1] != "t2" {
		t.Fatalf("expected [t1 t2] released in FIFO order, got %v", released)
	}

	for _, id := range []string{"t1", "t2"} {
		admitted, err := repo.IsAdmitted(id)
		if err != nil || !admitted {
			t.Errorf("%s: expected admitted=true, got %v (err=%v)", id, admitted, err)
		}
	}

	// t3 vẫn còn trong hàng đợi, CHƯA admitted, giờ ở position 0 (t1/t2 đã pop).
	admitted, _ := repo.IsAdmitted("t3")
	if admitted {
		t.Error("t3 should not be admitted yet")
	}
	pos, found, _ := repo.Position("trip-1", "t3")
	if !found || pos != 0 {
		t.Errorf("expected t3 at position 0, found=%v pos=%d", found, pos)
	}
}

func TestActiveResources_RemovedWhenQueueEmpty(t *testing.T) {
	repo := newTestRepo(t)

	must(t, repo.Enqueue("trip-1", "t1"))
	active, err := repo.ActiveResources()
	if err != nil || len(active) != 1 || active[0] != "trip-1" {
		t.Fatalf("expected [trip-1] active, got %v (err=%v)", active, err)
	}

	// Release hết vé duy nhất -> trip-1 phải bị loại khỏi active-queues.
	if _, err := repo.Release("trip-1", 1, 15*time.Minute); err != nil {
		t.Fatalf("release: %v", err)
	}
	active, err = repo.ActiveResources()
	if err != nil || len(active) != 0 {
		t.Fatalf("expected no active resources after queue emptied, got %v (err=%v)", active, err)
	}
}

func TestSubscribeAdmission_ReceivesSignalOnRelease(t *testing.T) {
	repo := newTestRepo(t)
	must(t, repo.Enqueue("trip-1", "t1"))

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ch, cleanup := repo.SubscribeAdmission(ctx, "t1")
	defer cleanup()

	// Cho subscriber kịp đăng ký với Redis trước khi publish — tránh race
	// (release quá nhanh, message tới trước khi subscribe xong).
	time.Sleep(100 * time.Millisecond)

	if _, err := repo.Release("trip-1", 1, 15*time.Minute); err != nil {
		t.Fatalf("release: %v", err)
	}

	select {
	case <-ch:
		// đúng — nhận được tín hiệu admitted
	case <-time.After(3 * time.Second):
		t.Fatal("did not receive admission signal within 3s")
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}
