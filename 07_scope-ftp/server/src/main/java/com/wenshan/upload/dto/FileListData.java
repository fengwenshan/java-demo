package com.wenshan.upload.dto;

import java.util.List;

/**
 * 契约 3.4 分页列表响应 data。
 */
public record FileListData(
        List<FileMeta> items,
        long total,
        int page,
        int size
) {
}
