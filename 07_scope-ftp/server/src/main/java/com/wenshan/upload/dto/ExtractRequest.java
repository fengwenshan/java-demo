package com.wenshan.upload.dto;

/**
 * 契约 3.19 zip 解压请求（JSON）：{@code path} 为相对 root 的 .zip 文件路径。
 */
public record ExtractRequest(String root, String path) {
}
