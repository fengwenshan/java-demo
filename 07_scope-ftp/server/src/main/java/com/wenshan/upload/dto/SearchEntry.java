package com.wenshan.upload.dto;

import java.time.Instant;

/**
 * 递归搜索结果条目：{@code parent} 为父目录相对 root 的路径（root 下为空字符串），
 * 目录 {@code size} 为 null。
 */
public record SearchEntry(
        String name,
        String path,
        String parent,
        String type,
        Long size,
        Instant modifiedAt
) {
}
