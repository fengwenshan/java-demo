package com.wenshan.upload.dto;

import java.util.List;

/**
 * 契约 3.14 递归搜索响应：{@code truncated} 表示达到扫描/深度/结果上限而截断。
 */
public record SearchData(
        String keyword,
        boolean truncated,
        List<SearchEntry> entries
) {
}
