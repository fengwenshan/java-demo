package com.wenshan.upload.dto;

import java.util.List;

/**
 * 契约 3.13 分片上传状态（断点续传）：{@code received} 为已接收分片 index 升序列表。
 */
public record ChunkStatusData(
        String uploadId,
        String name,
        long size,
        int chunkSize,
        List<Integer> received
) {
}
