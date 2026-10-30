package com.wenshan.upload.dto;

/**
 * 分片写入确认：{@code received} 为当前会话已接收的分片数量。
 */
public record ChunkAckData(
        String uploadId,
        int received
) {
}
