package com.wenshan.upload.dto;

/**
 * 契约 3.23 移动响应 data：移动后所在根、相对路径、类型（file/directory）与名字。
 */
public record MoveData(String root, String path, String type, String name) {
}
