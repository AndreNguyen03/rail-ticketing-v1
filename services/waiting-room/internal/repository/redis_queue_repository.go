package repository

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisQueueRepository implement QueueRepository bằng 1 Redis SORTED SET
// cho mỗi resource (điểm số = thời gian join, giữ đúng thứ tự vào trước ra
// trước) + 1 STRING key riêng có TTL cho mỗi ticket đã được admit (token).
// Không dùng DB nào khác — đúng quyết định kiến trúc "No database" trong
// docs/02-architecture.md.
type RedisQueueRepository struct {
	client *redis.Client
}

func NewRedisQueueRepository(client *redis.Client) *RedisQueueRepository {
	return &RedisQueueRepository{client: client}
}

const activeResourcesKey = "active-queues" // Redis SET — resource nào đang có người chờ

func queueKey(resource string) string         { return "queue:{" + resource + "}" }
func admittedKey(ticketID string) string      { return "admitted:" + ticketID }
func admissionChannel(ticketID string) string { return "admission-notify:" + ticketID }

func (r *RedisQueueRepository) Enqueue(resource, ticketID string) error {
	ctx := context.Background()
	// NX: nếu ticketID đã tồn tại trong sorted set thì KHÔNG ghi đè score —
	// join lại (retry mạng) không bị mất vị trí xếp hàng đã có.
	if err := r.client.ZAddNX(ctx, queueKey(resource), redis.Z{
		Score:  float64(time.Now().UnixNano()),
		Member: ticketID,
	}).Err(); err != nil {
		return err
	}
	// Đăng ký resource này vào danh sách "đang active" để releaseLoop biết mà release —
	// SADD idempotent, gọi lại nhiều lần cho cùng resource không sao.
	return r.client.SAdd(ctx, activeResourcesKey, resource).Err()
}

func (r *RedisQueueRepository) Position(resource, ticketID string) (int64, bool, error) {
	ctx := context.Background()
	rank, err := r.client.ZRank(ctx, queueKey(resource), ticketID).Result()
	if err == redis.Nil {
		return 0, false, nil // không còn trong hàng đợi — đã release hoặc chưa từng join
	}
	if err != nil {
		return 0, false, err
	}
	return rank, true, nil
}

func (r *RedisQueueRepository) Release(resource string, count int64, admittedTTL time.Duration) ([]string, error) {
	ctx := context.Background()
	// ZPOPMIN: lấy ra "count" phần tử có score NHỎ NHẤT (vào sớm nhất) — atomic,
	// 2 lần gọi Release song song không bao giờ trùng người được release.
	popped, err := r.client.ZPopMin(ctx, queueKey(resource), count).Result()
	if err != nil {
		return nil, err
	}

	ids := make([]string, 0, len(popped))
	for _, z := range popped {
		ticketID := z.Member.(string)
		ids = append(ids, ticketID)
		if err := r.client.Set(ctx, admittedKey(ticketID), resource, admittedTTL).Err(); err != nil {
			return ids, err
		}
		// PUBLISH sau khi đã SET admitted key — subscriber (SSE handler) thức dậy
		// và đọc lại thấy admitted=true ngay, không có race "nhận tín hiệu nhưng key chưa kịp ghi".
		// Không quan tâm PUBLISH có ai đang nghe hay không (fire-and-forget) — nếu SSE
		// handler không kết nối kịp lúc này, poll dự phòng ở handler vẫn bắt được sau đó.
		r.client.Publish(ctx, admissionChannel(ticketID), "1")
	}

	// Hàng đợi rỗng sau khi release → bỏ resource khỏi danh sách active,
	// tránh releaseLoop quét mãi 1 resource đã hết người (dọn rác, không bắt buộc
	// đúng — Enqueue sau đó sẽ SADD lại nếu có người mới join).
	if remaining, err := r.client.ZCard(ctx, queueKey(resource)).Result(); err == nil && remaining == 0 {
		r.client.SRem(ctx, activeResourcesKey, resource)
	}

	return ids, nil
}

func (r *RedisQueueRepository) ActiveResources() ([]string, error) {
	ctx := context.Background()
	return r.client.SMembers(ctx, activeResourcesKey).Result()
}

func (r *RedisQueueRepository) SubscribeAdmission(ctx context.Context, ticketID string) (<-chan struct{}, func()) {
	pubsub := r.client.Subscribe(ctx, admissionChannel(ticketID))
	out := make(chan struct{}, 1)

	go func() {
		defer close(out)
		select {
		case _, ok := <-pubsub.Channel():
			if ok {
				out <- struct{}{}
			}
		case <-ctx.Done(): // client đóng connection SSE hoặc timeout — dừng chờ, không rò rỉ goroutine
		}
	}()

	return out, func() { pubsub.Close() }
}

func (r *RedisQueueRepository) IsAdmitted(ticketID string) (bool, error) {
	ctx := context.Background()
	n, err := r.client.Exists(ctx, admittedKey(ticketID)).Result()
	return n > 0, err
}

func (r *RedisQueueRepository) QueueLength(resource string) (int64, error) {
	ctx := context.Background()
	return r.client.ZCard(ctx, queueKey(resource)).Result()
}
