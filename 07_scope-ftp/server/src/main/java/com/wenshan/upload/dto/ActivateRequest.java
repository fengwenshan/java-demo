package com.wenshan.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 契约 3.3 设备激活请求体。
 */
public record ActivateRequest(
        @NotBlank(message = "deviceId 不能为空")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "deviceId 必须是 UUID")
        String deviceId,

        @NotBlank(message = "deviceName 不能为空")
        @Size(min = 1, max = 64, message = "deviceName 长度必须为 1~64 字符")
        String deviceName
) {
}
