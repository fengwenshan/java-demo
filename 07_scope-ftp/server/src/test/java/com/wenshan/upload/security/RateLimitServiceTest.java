package com.wenshan.upload.security;

import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶限流单元测试（不启动 Spring）。
 */
class RateLimitServiceTest {

    @Test
    void consumesUntilBucketEmptyAndSeparatesKeys() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getRateLimit().setCapacity(2);
        properties.getSecurity().getRateLimit().setRefillPerSecond(0);
        RateLimitService service = new RateLimitService(properties);

        assertThat(service.tryConsume("demo-app|127.0.0.1", 1)).isTrue();
        assertThat(service.tryConsume("demo-app|127.0.0.1", 1)).isTrue();
        assertThat(service.tryConsume("demo-app|127.0.0.1", 1)).isFalse();
        // 不同 key 独立计数
        assertThat(service.tryConsume("demo-app|10.0.0.2", 1)).isTrue();
    }

    @Test
    void uploadCostConsumesMoreTokens() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getRateLimit().setCapacity(5);
        properties.getSecurity().getRateLimit().setRefillPerSecond(0);
        RateLimitService service = new RateLimitService(properties);

        assertThat(service.tryConsume("k", 5)).isTrue();
        assertThat(service.tryConsume("k", 1)).isFalse();

        properties.getSecurity().getRateLimit().setCapacity(6);
        service.clear();
        assertThat(service.tryConsume("k", 5)).isTrue();
        assertThat(service.tryConsume("k", 1)).isTrue();
    }

    @Test
    void tokenBucketRefillsOverTime() throws InterruptedException {
        RateLimitService.TokenBucket bucket = new RateLimitService.TokenBucket(1);
        assertThat(bucket.tryConsume(1, 1, 1000)).isTrue();
        assertThat(bucket.tryConsume(1, 1, 1000)).isFalse();
        Thread.sleep(20);
        assertThat(bucket.tryConsume(1, 1, 1000)).isTrue();
    }
}
