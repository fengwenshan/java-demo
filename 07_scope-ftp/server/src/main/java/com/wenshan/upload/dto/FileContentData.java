package com.wenshan.upload.dto;

/**
 * 契约 3.18 在线编辑：读取文本文件响应 data。
 *
 * <p>{@code sha256} 为当前内容 SHA-256，客户端保存时回传为 expectedSha256 做并发校验；
 * {@code editable} 固定 true（不可编辑场景直接返回 40000/40400 错误，不会返回 false）。</p>
 */
public record FileContentData(
        String path,
        String content,
        long size,
        String sha256,
        boolean editable
) {
}
