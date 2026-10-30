package com.wenshan.upload.dto;

import java.time.Instant;

/**
 * 目录浏览条目：{@code size} 仅文件有值，目录为 null。
 */
public record DirEntry(
        String name,
        String path,
        String type,
        Long size,
        Instant modifiedAt
) {
}
