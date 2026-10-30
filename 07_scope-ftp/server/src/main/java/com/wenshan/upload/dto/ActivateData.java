package com.wenshan.upload.dto;

import java.time.Instant;

/**
 * 契约 3.3 激活响应 data。
 */
public record ActivateData(
        String appKey,
        String deviceId,
        String deviceName,
        Instant boundAt,
        long serverTime
) {
}
