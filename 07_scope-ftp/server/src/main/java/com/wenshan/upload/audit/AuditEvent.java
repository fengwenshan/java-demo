package com.wenshan.upload.audit;

/**
 * 一条客户端操作审计记录（JSONL 一行）。字段尽量完整，但绝不包含签名/nonce/密钥/文件正文。
 */
public record AuditEvent(
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
