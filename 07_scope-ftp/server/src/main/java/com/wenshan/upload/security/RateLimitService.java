package com.wenshan.upload.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wenshan.upload.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流（key = appKey + 客户端 IP）。
 *
 * <p>默认容量 60、每秒补 1 个；上传接口单次消耗 5 个令牌。</p>
 */
@Component
public class RateLimitService {

    private static final int MAX_BUCKETS = 10_000;

    private final Cache<String, TokenBucket> buckets;
    private final AppProperties properties;

    public RateLimitService(AppProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    /** 尝试消耗令牌；返回 false 表示触发限流。 */
    public boolean tryConsume(String key, int cost) {
        AppProperties.RateLimit config = properties.getSecurity().getRateLimit();
        TokenBucket bucket = buckets.get(key, ignored -> new TokenBucket(config.getCapacity()));
        return bucket.tryConsume(cost, config.getCapacity(), config.getRefillPerSecond());
    }

    /** 测试辅助：清空所有令牌桶。 */
    public void clear() {
        buckets.invalidateAll();
    }

    /**
     * 单个令牌桶，线程安全。
     */
    public static final class TokenBucket {

        private final AtomicLong lastRefillNanos = new AtomicLong();
        private double tokens;

        public TokenBucket(double capacity) {
            this.tokens = capacity;
            this.lastRefillNanos.set(System.nanoTime());
        }

        public synchronized boolean tryConsume(int cost, double capacity, double refillPerSecond) {
            long now = System.nanoTime();
            long previous = lastRefillNanos.getAndSet(now);
            long elapsedNanos = now - previous;
            if (elapsedNanos > 0 && refillPerSecond > 0) {
                double refill = (elapsedNanos / 1_000_000_000.0D) * refillPerSecond;
                tokens = Math.min(capacity, tokens + refill);
            }
            if (tokens + 1e-9 >= cost) {
                tokens -= cost;
                return true;
            }
            return false;
        }

        public synchronized double availableTokens() {
            return tokens;
        }
    }
}
