package com.wenshan.upload.dto;

/**
 * 契约 3.11 {@code POST /api/files/upload/init} 请求体（JSON）。
 *
 * <p>{@code size} 用包装类型以区分「未传」与「0」（未传属于 40000）。
 * {@code root} 为目标存储根 id（可选，缺省=默认根）。</p>
 */
public record ChunkInitRequest(
        String name,
        String dir,
        Long size,
        String sha256,
        String description,
        String root
) {

    /** 兼容旧测试/旧客户端的构造器（不带 root，等价默认根）。 */
    public ChunkInitRequest(String name, String dir, Long size, String sha256, String description) {
        this(name, dir, size, sha256, description, null);
    }
}
