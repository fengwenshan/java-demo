package com.wenshan.upload.dto;

/**
 * 契约 3.19 zip 解压响应 data：{@code targetDir} 相对 root，entries 为文件数，bytes 为解压总字节。
 */
public record ExtractData(String targetDir, int entries, long bytes) {
}
