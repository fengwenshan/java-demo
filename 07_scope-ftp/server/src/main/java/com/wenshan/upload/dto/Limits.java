package com.wenshan.upload.dto;

/**
 * 服务端限制信息。
 */
public record Limits(
        long maxFileSize
) {
}
