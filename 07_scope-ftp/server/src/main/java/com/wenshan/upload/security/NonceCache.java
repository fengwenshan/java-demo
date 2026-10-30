package com.wenshan.upload.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wenshan.upload.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * nonce 防重放缓存（Caffeine，TTL 默认 600s）。
 *
 * <p>只在签名校验通过后调用 {@link #claim} 写入，避免无效签名污染缓存。</p>
 */
@Component
public class NonceCache {

    private static final int MAX_ENTRIES = 200_000;

    private final Cache<String, Boolean> usedNonces;

    public NonceCache(AppProperties properties) {
        long ttlMillis = Math.max(1L, properties.getSecurity().getNonceTtlMs());
        this.usedNonces = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
    }

    /**
     * 原子地占用 nonce。
     *
     * @return true 表示首次使用；false 表示 10 分钟内已使用过（重放）
     */
    public boolean claim(String appKey, String nonce) {
        String cacheKey = appKey + ':' + nonce;
        return usedNonces.asMap().putIfAbsent(cacheKey, Boolean.TRUE) == null;
    }

    /** 测试辅助：清空缓存。 */
    public void clear() {
        usedNonces.invalidateAll();
    }
}
