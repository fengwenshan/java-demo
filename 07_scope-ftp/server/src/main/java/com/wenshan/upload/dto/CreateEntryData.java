package com.wenshan.upload.dto;

/**
 * {@code POST /api/files/create} 响应 data：新条目所在根、相对路径、类型与名称。
 */
public record CreateEntryData(String root, String path, String type, String name) {
}
