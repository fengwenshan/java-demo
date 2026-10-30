package com.wenshan.upload.dto;

import java.time.Instant;

/**
 * 已绑定设备信息；未激活时整体为 null。
 */
public record DeviceInfo(
        String deviceId,
        String deviceName,
        Instant boundAt
) {
}
