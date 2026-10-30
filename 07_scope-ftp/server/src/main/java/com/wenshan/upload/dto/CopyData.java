package com.wenshan.upload.dto;

/**
 * 契约 3.22 拷贝响应 data：副本所在根、相对路径、类型（file/directory）与副本名。
 */
public record CopyData(String root, String path, String type, String name) {
}
