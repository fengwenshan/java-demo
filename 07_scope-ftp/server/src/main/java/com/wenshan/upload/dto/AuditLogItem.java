package com.wenshan.upload.dto;

/**
 * 审计日志查询结果中的一条记录（字段与 JSONL 审计事件一致，额外带数据库自增 id）。
 */
public record AuditLogItem(
        long id,
        String ts,
        String requestId,
        String appKey,
        String clientName,
        String deviceId,
        String clientIp,
        String method,
        String path,
        String query,
        String action,
        String root,
        String targetRoot,
        String pathParam,
        String targetDir,
        String sessionId,
        String terminalCommand,
        String body,
        String contentSha256,
        Long requestBytes,
        Long responseBytes,
        long durationMs,
        int httpStatus,
        boolean success,
        String error,
        String userAgent,
        String fileName
) {
}
