package com.wenshan.upload.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.audit.AuditContext;
import com.wenshan.upload.audit.AuditLogService;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 契约 2.6 签名校验过滤器（除 /api/health 与 OPTIONS 外，/api/** 全部校验）。
 *
 * <p>校验顺序严格按契约：必需请求头 → appKey → 时间戳窗口 → HMAC 常量时间比较 →
 * nonce 防重放（签名通过后才写入）→ 设备绑定 → 令牌桶限流。</p>
 *
 * <p>JSON 请求体经 {@link CachedBodyHttpServletRequest} 缓存后计算 payloadHash；
 * multipart 与 application/octet-stream（分片上传）请求**不读取/不缓存 body**，
 * 使用 X-Content-Sha256（文件内容 / 分片内容的 SHA-256）参与签名，
 * 实际内容哈希由上传业务落盘时复算比对。这样 8MB 分片不会受 JSON 缓存上限约束。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SignatureFilter extends OncePerRequestFilter {

    public static final String ATTR_APP_KEY = "com.wenshan.upload.signature.appKey";
    public static final String ATTR_DEVICE_ID = "com.wenshan.upload.signature.deviceId";
    public static final String ATTR_CLIENT_NAME = "com.wenshan.upload.signature.clientName";
    public static final String ATTR_CONTENT_SHA256 = "com.wenshan.upload.signature.contentSha256";

    private static final Logger log = LoggerFactory.getLogger(SignatureFilter.class);

    private static final String HEADER_APP_KEY = "X-App-Key";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_NONCE = "X-Nonce";
    private static final String HEADER_SIGNATURE = "X-Signature";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";
    private static final String HEADER_CONTENT_SHA256 = "X-Content-Sha256";

    private static final Pattern NONCE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_CACHED_BODY_BYTES = 1_048_576;
    private static final String HEALTH_PATH = "/api/health";
    private static final String API_PREFIX = "/api/";
    private static final String VERIFY_PATH = "/api/auth/verify";
    private static final String ACTIVATE_PATH = "/api/auth/activate";
    private static final String FILES_PREFIX = "/api/files";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String UPLOAD_INIT_PATH = "/api/files/upload/init";
    private static final String UPLOAD_SEGMENT = "/api/files/upload/";
    private static final String EXTRACT_PATH = "/api/files/extract";
    private static final String COPY_PATH = "/api/files/copy";
    private static final String MOVE_PATH = "/api/files/move";
    private static final String TERMINAL_EXEC_PATH = "/api/terminal/exec";

    private final SignatureService signatureService;
    private final AuthService authService;
    private final NonceCache nonceCache;
    private final RateLimitService rateLimitService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final ClientGuardService clientGuardService;

    public SignatureFilter(SignatureService signatureService,
                           AuthService authService,
                           NonceCache nonceCache,
                           RateLimitService rateLimitService,
                           AppProperties properties,
                           ObjectMapper objectMapper,
                           AuditLogService auditLogService,
                           ClientGuardService clientGuardService) {
        this.signatureService = signatureService;
        this.authService = authService;
        this.nonceCache = nonceCache;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.clientGuardService = clientGuardService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 签名仍按「实际收到的路径」计算（relay 请求是 /api/relay + 原始路径）；
        // 设备绑定/限流成本/activate 顺序等业务判断使用剥离 /api/relay 后的有效路径。
        String rawPath = request.getRequestURI();
        String path = effectivePath(rawPath);
        String method = request.getMethod();
        if (shouldSkip(rawPath, method)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 审计：每个签名接口请求（成功/失败/被拒）都记录一条；requestId 同时写响应头
        String requestId = auditLogService.newRequestId();
        response.setHeader("X-Request-Id", requestId);
        long startNanos = System.nanoTime();
        String appKey = request.getHeader(HEADER_APP_KEY);
        String deviceId = request.getHeader(HEADER_DEVICE_ID);
        String clientName = null;
        HttpServletRequest requestToUse = request;
        int httpStatus = ErrorCode.INTERNAL_ERROR.getHttpStatus();
        boolean success = false;
        String error = null;
        try {
            requestToUse = authenticate(request, rawPath, method);
            clientName = (String) requestToUse.getAttribute(ATTR_CLIENT_NAME);
            filterChain.doFilter(requestToUse, response);
            httpStatus = response.getStatus();
            success = httpStatus < 400;
            if (success && VERIFY_PATH.equals(path)) {
                // verify 成功：记录 MAC+IP 的 verify 状态，供随后 activate 的顺序校验使用
                clientGuardService.markVerified(clientIpOf(request), clientMacOf(request), appKey, deviceId);
            }
        } catch (RejectedException e) {
            httpStatus = e.errorCode.getHttpStatus();
            error = auditError(e.errorCode.getCode(), e.errorCode.getMessage());
            writeError(response, e.errorCode, requestId);
        } catch (Exception e) {
            log.error("签名过滤器处理异常", e);
            httpStatus = ErrorCode.INTERNAL_ERROR.getHttpStatus();
            error = auditError(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
            writeError(response, ErrorCode.INTERNAL_ERROR, requestId);
        } finally {
            try {
                Integer businessCode = AuditContext.errorCode(request);
                if (businessCode != null) {
                    error = auditError(businessCode, AuditContext.errorMessage(request));
                    httpStatus = response.getStatus();
                    success = false;
                }
                AuditClient name = new AuditClient(appKey, clientName, deviceId);
                auditLogService.record(auditLogService.buildEvent(requestToUse, response, requestId,
                        startNanos, httpStatus, success, error, name.appKey(), name.clientName(), name.deviceId()));
            } catch (RuntimeException auditFailure) {
                log.warn("记录审计日志失败（不影响请求）: {}", auditFailure.toString());
            }
        }
    }

    private static String auditError(int code, String message) {
        return code + " " + (message == null ? "" : message);
    }

    private record AuditClient(String appKey, String clientName, String deviceId) {
    }

    private boolean shouldSkip(String path, String method) {
        return "OPTIONS".equalsIgnoreCase(method)
                || path == null
                || !path.startsWith(API_PREFIX)
                || HEALTH_PATH.equals(path);
    }

    /** 按契约 2.6 顺序校验，通过后返回供下游使用的请求（JSON 请求为缓存 body 的 wrapper）。 */
    private HttpServletRequest authenticate(HttpServletRequest request, String path, String method) {
        // 业务路径（剥离 relay 前缀），用于设备绑定/限流成本/调用顺序判断；签名仍用 path。
        String businessPath = effectivePath(path);
        // 1. 必需请求头
        String appKey = request.getHeader(HEADER_APP_KEY);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String providedSignature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(appKey) || isBlank(timestamp) || isBlank(nonce) || isBlank(providedSignature)) {
            throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
        }
        if (!NONCE_PATTERN.matcher(nonce).matches()) {
            throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
        }

        // 2. appKey
        AppProperties.Client client = authService.findEnabledClient(appKey);
        if (client == null) {
            throw new RejectedException(ErrorCode.CLIENT_UNAUTHORIZED);
        }

        // 3. 时间戳窗口
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
        }
        long now = signatureService.nowMillis();
        long window = properties.getSecurity().getTimestampWindowMs();
        if (timestampMillis < now - window || timestampMillis > now + window) {
            throw new RejectedException(ErrorCode.REQUEST_EXPIRED);
        }

        // 4. payloadHash + 签名（常量时间比较）
        //    multipart（单文件上传）与 octet-stream（分片上传）都不缓存 body，用 X-Content-Sha256 参与签名
        String contentType = request.getContentType();
        boolean usesHeaderSha = usesContentShaHeader(contentType);
        HttpServletRequest requestToUse = request;
        String payloadHash;
        String contentSha256 = null;
        if (usesHeaderSha) {
            contentSha256 = request.getHeader(HEADER_CONTENT_SHA256);
            if (isBlank(contentSha256)) {
                throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
            }
            contentSha256 = contentSha256.toLowerCase(Locale.ROOT);
            if (!SHA256_HEX_PATTERN.matcher(contentSha256).matches()) {
                throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
            }
            payloadHash = contentSha256;
        } else {
            byte[] body = readAndCacheBody(request, contentType);
            payloadHash = SignatureService.sha256Hex(body);
            requestToUse = new CachedBodyHttpServletRequest(request, body);
        }
        String canonicalString = signatureService.canonicalString(
                appKey,
                timestamp,
                nonce,
                method.toUpperCase(Locale.ROOT),
                path,
                CanonicalQuery.canonicalize(request.getQueryString()),
                payloadHash);
        if (!signatureService.verify(client.getAppSecret(), canonicalString, providedSignature)) {
            throw new RejectedException(ErrorCode.INVALID_SIGNATURE);
        }

        // 5. nonce 防重放（签名通过后才写入）
        if (!nonceCache.claim(appKey, nonce)) {
            throw new RejectedException(ErrorCode.REQUEST_REPLAYED);
        }

        // 6. 设备绑定（仅文件类接口；relay 请求按剥离前缀后的业务路径判断）
        String deviceId = request.getHeader(HEADER_DEVICE_ID);
        if (properties.getSecurity().isDeviceBindingEnabled() && isFileApi(businessPath)
                && !authService.isDeviceAllowed(appKey, deviceId)) {
            throw new RejectedException(ErrorCode.DEVICE_UNAUTHORIZED);
        }

        // 7. 令牌桶限流（appKey + IP，上传/初始化/完成/zip 解压/拷贝移动/终端执行消耗更多令牌）
        int cost = (isUploadCost(businessPath, method) || isExtractCost(businessPath, method)
                || isCopyOrMoveCost(businessPath, method) || isTerminalCost(businessPath, method))
                ? properties.getSecurity().getRateLimit().getUploadCost()
                : 1;
        if (!rateLimitService.tryConsume(appKey + "|" + clientIp(request), cost)) {
            throw new RejectedException(ErrorCode.RATE_LIMITED);
        }

        // 8. 调用顺序：activate 前必须有同 MAC+IP 的近期 verify（仅身份强制开启时生效）
        if (ACTIVATE_PATH.equals(businessPath)) {
            clientGuardService.requireVerifiedBeforeActivate(
                    clientIpOf(request), clientMacOf(request), appKey, deviceId);
        }

        requestToUse.setAttribute(ATTR_APP_KEY, appKey);
        requestToUse.setAttribute(ATTR_CLIENT_NAME, client.getName());
        if (deviceId != null && !deviceId.isBlank()) {
            requestToUse.setAttribute(ATTR_DEVICE_ID, deviceId);
        }
        if (contentSha256 != null) {
            requestToUse.setAttribute(ATTR_CONTENT_SHA256, contentSha256);
        }
        return requestToUse;
    }

    /** 读取并缓存请求体；非 UTF-8 请求体 → 40000（契约第 6 节）。 */
    private byte[] readAndCacheBody(HttpServletRequest request, String contentType) {
        Charset charset = parseCharset(contentType);
        if (charset != null && !StandardCharsets.UTF_8.equals(charset)) {
            throw new RejectedException(ErrorCode.BAD_REQUEST);
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_CACHED_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new RejectedException(ErrorCode.BAD_REQUEST);
        }
        if (body.length > MAX_CACHED_BODY_BYTES) {
            throw new RejectedException(ErrorCode.BAD_REQUEST);
        }
        if (isJson(contentType)) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(body));
            } catch (CharacterCodingException e) {
                throw new RejectedException(ErrorCode.BAD_REQUEST);
            }
        }
        return body;
    }

    private static String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    /** 客户端 IP（优先取 ClientGuardFilter 解析的 attribute，未经过该过滤器时回退 remoteAddr）。 */
    private static String clientIpOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(ClientGuardService.ATTR_CLIENT_IP);
        if (attribute instanceof String value) {
            return value;
        }
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? null : remoteAddress.trim();
    }

    /** 客户端 MAC（优先取 attribute，否则从 X-Device-Mac 头规范化）。 */
    private static String clientMacOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(ClientGuardService.ATTR_CLIENT_MAC);
        if (attribute instanceof String value) {
            return value;
        }
        return ClientGuardService.normalizeMac(request.getHeader(ClientGuardService.HEADER_DEVICE_MAC));
    }

    /** relay 请求的业务有效路径：/api/relay/api/... → /api/...（其他路径原样返回）。 */
    private static String effectivePath(String path) {
        if (path != null && path.startsWith("/api/relay/api/")) {
            return path.substring("/api/relay".length());
        }
        return path;
    }

    private static boolean isFileApi(String path) {
        return path.startsWith(FILES_PREFIX);
    }

    /**
     * 单文件上传 POST /api/files/upload、分片初始化 .../upload/init、分片完成 .../upload/{id}/complete、
     * zip 解压 POST /api/files/extract 消耗 uploadCost 个令牌；其余接口 1 个。
     */
    private static boolean isUploadCost(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        if (UPLOAD_PATH.equals(path) || UPLOAD_INIT_PATH.equals(path)) {
            return true;
        }
        return path.startsWith(UPLOAD_SEGMENT) && path.endsWith("/complete");
    }

    /** zip 解压与分片上传同级写操作：消耗 uploadCost 个令牌。 */
    private static boolean isExtractCost(String path, String method) {
        return "POST".equalsIgnoreCase(method) && EXTRACT_PATH.equals(path);
    }

    /** 拷贝/移动是高 IO 写操作：与上传/解压同级，消耗 uploadCost 个令牌。 */
    private static boolean isCopyOrMoveCost(String path, String method) {
        return "POST".equalsIgnoreCase(method) && (COPY_PATH.equals(path) || MOVE_PATH.equals(path));
    }

    /** 远程终端命令执行是高权限操作：消耗 uploadCost 个令牌。 */
    private static boolean isTerminalCost(String path, String method) {
        return "POST".equalsIgnoreCase(method) && TERMINAL_EXEC_PATH.equals(path);
    }

    /** multipart（单文件上传）或 application/octet-stream（分片上传）都需要 X-Content-Sha256 参与签名。 */
    private static boolean usesContentShaHeader(String contentType) {
        return isMultipart(contentType) || isOctetStream(contentType);
    }

    private static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static boolean isOctetStream(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_OCTET_STREAM
                    .isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    private static Charset parseCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return mediaType.getCharset();
        } catch (InvalidMediaTypeException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String requestId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        if (requestId != null) {
            response.setHeader("X-Request-Id", requestId);
        }
        response.setStatus(errorCode.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode));
    }

    /** 过滤器内部拒绝，携带契约错误码（同包内 ClientGuardService/ClientGuardFilter 共用）。 */
    static final class RejectedException extends RuntimeException {

        final ErrorCode errorCode;

        RejectedException(ErrorCode errorCode) {
            super(errorCode.getMessage(), null, false, false);
            this.errorCode = errorCode;
        }
    }
}
