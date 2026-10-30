package com.wenshan.upload.dto;

/**
 * 契约 3.11 分片上传初始化响应：{@code chunkSize} 由服务端决定，后续按该大小切片。
 */
public record ChunkInitData(
        String uploadId,
        int chunkSize
) {
}
