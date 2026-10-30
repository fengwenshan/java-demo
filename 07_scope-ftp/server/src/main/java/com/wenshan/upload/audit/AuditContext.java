package com.wenshan.upload.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计上下文属性：GlobalExceptionHandler 把业务错误 code/message 写进 request attribute，
 * SignatureFilter 在请求结束时统一读取并落审计日志（不依赖响应体缓存）。
 */
public final class AuditContext {

    public static final String ATTR_ERROR_CODE = "com.wenshan.upload.audit.errorCode";
    public static final String ATTR_ERROR_MESSAGE = "com.wenshan.upload.audit.errorMessage";
    /** 业务接口落盘后回填实际文件名，供 multipart（body 不缓存）的审计列表展示。 */
    public static final String ATTR_FILE_NAME = "com.wenshan.upload.audit.fileName";

    private AuditContext() {
    }

    public static void setError(HttpServletRequest request, int code, String message) {
        if (request == null) {
            return;
        }
        request.setAttribute(ATTR_ERROR_CODE, code);
        request.setAttribute(ATTR_ERROR_MESSAGE, message);
    }

    public static Integer errorCode(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTR_ERROR_CODE);
        return value instanceof Integer code ? code : null;
    }

    public static String errorMessage(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTR_ERROR_MESSAGE);
        return value == null ? null : String.valueOf(value);
    }

    public static void setFileName(HttpServletRequest request, String fileName) {
        if (request == null || fileName == null || fileName.isBlank()) {
            return;
        }
        request.setAttribute(ATTR_FILE_NAME, fileName);
    }

    public static String fileName(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTR_FILE_NAME);
        return value == null ? null : String.valueOf(value);
    }
}
