package com.wenshan.upload.dto;

/**
 * 契约 3.18 在线编辑：覆盖保存请求（JSON）。
 *
 * <p>{@code content} 允许空字符串但字段必须存在；{@code expectedSha256} 可选，
 * 提供时必须与当前文件内容 SHA-256 一致，否则视为并发冲突拒绝保存。</p>
 */
public record ContentSaveRequest(
        String root,
        String path,
        String content,
        String expectedSha256
) {
}
