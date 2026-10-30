package com.wenshan.upload.dto;

/**
 * 契约 3.18 在线编辑：覆盖保存响应 data。
 */
public record ContentSaveData(String path, long size, String sha256) {
}
