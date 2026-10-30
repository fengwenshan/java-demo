package com.wenshan.upload.store;

import java.time.Instant;

/**
 * 设备绑定记录（持久化到 {@code meta-dir/devices.json}）。
 *
 * <p>对外契约里的 {@code boundAt} 即本记录的 {@code createdAt}。</p>
 */
public record DeviceRecord(
        String appKey,
        String deviceId,
        String deviceName,
        Instant createdAt,
        Instant lastSeenAt
) {
}
