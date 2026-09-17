package vn.railticketing.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Redis Lua hot path: atomic hold/release. PG is truth, Redis failure falls back to DB. */
@Service
public class RedisInventoryService {

    private static final Logger log = LoggerFactory.getLogger(RedisInventoryService.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List<Object>> holdScript;
    private final DefaultRedisScript<Long> releaseScript;

    @Value("${inventory.redis.enabled:false}")
    private boolean enabled;

    public RedisInventoryService(StringRedisTemplate redis,
                                 @Qualifier("holdScript") DefaultRedisScript<List<Object>> holdScript,
                                 @Qualifier("releaseScript") DefaultRedisScript<Long> releaseScript) {
        this.redis = redis;
        this.holdScript = holdScript;
        this.releaseScript = releaseScript;
    }

    public boolean isEnabled() { return enabled; }

    public RedisHoldResult tryHold(Long tripId, String berthClass, int journeyMask, int quantity, String holdId, long ttlSeconds) {
        if (!enabled) return null;
        try {
            String invKey = "inv:{" + tripId + "}:" + (berthClass != null ? berthClass : "ALL");
            String holdKey = "hold:{" + tripId + "}:" + holdId;
            int offset = ThreadLocalRandom.current().nextInt(10000);
            List<String> keys = List.of(invKey, holdKey);
            List<?> result = redis.execute(holdScript, keys,
                    String.valueOf(journeyMask), String.valueOf(quantity), holdId, String.valueOf(ttlSeconds), String.valueOf(offset));
            if (result == null || result.size() < 3) return null;
            long ok = ((Number) result.get(0)).longValue();
            String ids = (String) result.get(1);
            String status = (String) result.get(2);
            if (ok == 1) {
                return new RedisHoldResult(ids, status);
            } else {
                return new RedisHoldResult(null, status); // No berth: return status, no exception.
            }
        } catch (Exception e) {
            log.warn("Redis hold failed, fallback to DB: {}", e.getMessage());
            return null;
        }
    }

    public boolean tryRelease(Long tripId, String berthClass, String holdId, int journeyMask) {
        if (!enabled) return false;
        try {
            String invKey = "inv:{" + tripId + "}:" + (berthClass != null ? berthClass : "ALL");
            String holdKey = "hold:{" + tripId + "}:" + holdId;
            Long r = redis.execute(releaseScript, List.of(invKey, holdKey), String.valueOf(journeyMask));
            return r != null && r == 1;
        } catch (Exception e) {
            log.warn("Redis release failed: {}", e.getMessage());
            return false;
        }
    }

    public record RedisHoldResult(String berthIdsCsv, String status) {
        public boolean held() { return berthIdsCsv != null && !berthIdsCsv.isBlank(); }
    }
}
