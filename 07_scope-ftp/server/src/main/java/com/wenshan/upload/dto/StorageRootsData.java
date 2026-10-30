package com.wenshan.upload.dto;

import java.util.List;

/**
 * 契约：{@code GET /api/storage/roots} 响应 data。
 * {@code defaultRootId} 为接口不传 root 时使用的根 id；{@code roots} 保持配置顺序。
 */
public record StorageRootsData(
        String defaultRootId,
        List<StorageRootInfo> roots
) {
}
