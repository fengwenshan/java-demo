package com.wenshan.upload.dto;

import java.time.Instant;

/**
 * 契约 3.4 文件元数据。
 *
 * <p>{@code rootId} 为文件所属存储根 id（多根客户端展示/操作时需要）；旧单根记录返回 {@code default}
 * 或该根的实际 id（向后兼容新增字段）。</p>
 */
public record FileMeta(
        String id,
        String name,
        String description,
        long size,
        String contentType,
        String sha256,
        Instant createdAt,
        String rootId
) {

    /** 兼容旧调用方的构造器（不带 rootId）。 */
    public FileMeta(String id, String name, String description, long size, String contentType,
                    String sha256, Instant createdAt) {
        this(id, name, description, size, contentType, sha256, createdAt, null);
    }
}
