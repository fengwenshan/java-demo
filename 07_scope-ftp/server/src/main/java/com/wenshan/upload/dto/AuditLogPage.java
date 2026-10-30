package com.wenshan.upload.dto;

import java.util.List;

/**
 * 审计日志分页结果（按 created_at DESC, id DESC）。
 */
public record AuditLogPage(
        List<AuditLogItem> items,
        long total,
        int page,
        int size,
        boolean hasMore
) {
}
