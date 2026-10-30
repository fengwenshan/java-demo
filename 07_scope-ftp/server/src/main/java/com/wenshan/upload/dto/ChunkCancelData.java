package com.wenshan.upload.dto;

/**
 * 取消分片上传响应：{@code uploadId} 为被清理的会话。
 */
public record ChunkCancelData(
        String uploadId
) {
}
