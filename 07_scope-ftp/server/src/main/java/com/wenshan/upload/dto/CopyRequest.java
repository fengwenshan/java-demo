package com.wenshan.upload.dto;

/**
 * 契约 3.22 拷贝请求（JSON）：{@code path} 为相对指定 root 的文件或目录路径；
 * 拷贝目标为源同父目录下的副本（名字追加 {@code _副本}）。
 */
public record CopyRequest(String root, String path) {
}
