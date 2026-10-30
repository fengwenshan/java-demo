package com.wenshan.upload.controller;

import com.wenshan.upload.audit.AuditCategory;
import com.wenshan.upload.audit.AuditStore;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.AuditLogPage;
import com.wenshan.upload.security.SignatureFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 审计日志查询接口（签名接口，令牌消耗 1）。
 *
 * <p>{@code GET /api/audit/logs}：强制只返回当前签名 appKey 的审计记录，按时间倒序分页。
 * 支持 page/size/action/category/success/keyword/from/to；category=both 返回文件/终端操作 + 连接日志；size 上限 200；查询自身也会被审计（action=audit.list）。</p>
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditStore auditStore;

    public AuditController(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    @GetMapping("/logs")
    public ApiResponse<AuditLogPage> logs(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "success", required = false) Boolean success,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        if (page < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须 >= 1");
        }
        if (size < 1 || size > AuditStore.MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "size 必须在 1.." + AuditStore.MAX_PAGE_SIZE + " 之间");
        }
        String normalizedCategory = blankToNull(category);
        if (!AuditCategory.isKnown(normalizedCategory)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "category 只支持 operation、connection 或 both");
        }
        Long fromMillis = parseTime(from, "from");
        Long toMillis = parseTime(to, "to");
        if (fromMillis != null && toMillis != null && fromMillis > toMillis) {
            throw new BizException(ErrorCode.BAD_REQUEST, "from 不能晚于 to");
        }
        return ApiResponse.ok(auditStore.query(new AuditStore.Query(
                appKey, page, size, blankToNull(action), AuditCategory.actions(normalizedCategory),
                success, blankToNull(keyword), fromMillis, toMillis)));
    }

    private static Long parseTime(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(raw).toEpochMilli();
            } catch (DateTimeParseException e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        name + " 时间格式非法，应为 ISO-8601（如 2026-10-30T01:02:03Z）");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
