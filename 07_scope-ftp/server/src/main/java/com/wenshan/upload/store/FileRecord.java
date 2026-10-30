package com.wenshan.upload.store;

import java.time.Instant;

/**
 * 文件元数据记录（持久化到 {@code meta-dir/files.json}）。
 *
 * <p>{@code rootId} 标识文件归属的存储根 id（多根隔离）：旧版单根记录没有该字段，
 * 读取时为 {@code null}，由业务层按「记录所在根」解析（等价于旧行为 default 根）。</p>
 */
public record FileRecord(
        String id,
        String appKey,
        String deviceId,
        String name,
        String description,
        long size,
        String contentType,
        String sha256,
        String storedPath,
        Instant createdAt,
        String rootId
) {

    /** 兼容旧测试/旧代码的构造器（不带 rootId）。 */
    public FileRecord(String id, String appKey, String deviceId, String name, String description,
                      long size, String contentType, String sha256, String storedPath, Instant createdAt) {
        this(id, appKey, deviceId, name, description, size, contentType, sha256, storedPath, createdAt, null);
    }
}
