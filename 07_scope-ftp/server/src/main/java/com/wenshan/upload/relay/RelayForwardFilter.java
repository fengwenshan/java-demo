package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.audit.RelayViaCodec;
import com.wenshan.upload.audit.RelayViaHop;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.security.CachedBodyHttpServletRequest;
import com.wenshan.upload.security.SignatureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多级转发入口过滤器（A/B/C 作为中转节点时生效）。
 *
 * <p>只处理 {@code /api/relay/api/**}：{@link SignatureFilter} 先用本跳 secret 验签外层请求，
 * 本过滤器再弹出 {@code X-Relay-Chain} 第 0 跳、校验链头与请求头一致、追加 {@code X-Relay-Via}
 * 后把请求原样流式转发给下一跳；下一跳仍是中转节点时继续带剩余链，最终目标只多一个 Via 头。</p>
 *
 * <p>必须是 Filter 而非 Controller：Spring MVC 在进入 Handler 前就会解析 multipart，
 * Filter 在 DispatcherServlet 之前可以拿到原始流做无缓冲转发（8MB 分片/大文件下载不会 OOM）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RelayForwardFilter extends OncePerRequestFilter {

    /** 转发入口前缀；原始业务路径 = requestURI 去掉本前缀。 */
    static final String PREFIX = "/api/relay";
    static final String HEADER_CHAIN = "X-Relay-Chain";
    static final String HEADER_VIA = "X-Relay-Via";
    static final String HEADER_RELAY_REQUEST_ID = "X-Relay-Request-Id";
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String HEADER_DEVICE_MAC = "X-Device-Mac";
    static final String HEADER_CONTENT_SHA256 = "X-Content-Sha256";

    private static final int STREAM_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1_048_576;
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;

    /** 下游成功响应要透传的响应头（Content-Length 单独处理）。 */
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            "Content-Type", "Content-Disposition", "Content-Length",
            "Cache-Control", "ETag", "Last-Modified", "Content-Encoding",
            "Accept-Ranges", "Content-Range", "X-Content-Sha256");

    private final AppProperties properties;
    private final RelayChainCodec chainCodec;
    private final RelayViaCodec viaCodec;
    private final RelayUrlGuard urlGuard;
    private final RelayErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RelayForwardFilter(
            AppProperties properties,
            RelayChainCodec chainCodec,
            RelayViaCodec viaCodec,
            RelayUrlGuard urlGuard,
            RelayErrorMapper errorMapper,
            ObjectMapper objectMapper,
            @Qualifier("relayHttpClient") HttpClient httpClient) {
        this.properties = properties;
        this.chainCodec = chainCodec;
        this.viaCodec = viaCodec;
        this.urlGuard = urlGuard;
        this.errorMapper = errorMapper;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path == null
                || !path.startsWith(PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 转发请求不再进入 DispatcherServlet；本跳的审计仍由 SignatureFilter 的 finally 记录。
        if (!properties.getRelay().isEnabled()) {
            writeError(response, ErrorCode.RELAY_DISABLED, ErrorCode.RELAY_DISABLED.getMessage());
            return;
        }
        try {
            forward(request, response);
        } catch (BizException e) {
            writeError(response, e.getErrorCode(), e.getMessage());
        } catch (HttpTimeoutException e) {
            writeError(response, ErrorCode.RELAY_TIMEOUT, "转发目标超时");
        } catch (IOException e) {
            writeError(response, ErrorCode.RELAY_TARGET_UNREACHABLE, "转发目标不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(response, ErrorCode.RELAY_TARGET_UNREACHABLE, "转发目标不可达");
        }
    }

    private void forward(HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        String originalPath = validateOriginalPath(request.getRequestURI());

        List<RelayHop> chain = chainCodec.decode(
                request.getHeader(HEADER_CHAIN),
                properties.getRelay().getMaxHops(),
                properties.getRelay().getMaxChainHeaderBytes());
        if (chain.size() < 2) {
            throw invalid("转发链没有下一跳");
        }

        RelayHop head = chain.get(0);
        verifyHeadMatchesRequest(request, head);

        List<RelayHop> remaining = List.copyOf(chain.subList(1, chain.size()));
        RelayHop next = remaining.get(0);
        URI base = urlGuard.validate(next.url());
        String baseUrl = base.toString().replaceAll("/+$", "");
        // 下一跳仍是中转节点：目标路径继续带 /api/relay 前缀；最终目标只带原始业务路径。
        String targetPath = remaining.size() == 1 ? originalPath : PREFIX + originalPath;
        URI target = buildTargetUri(baseUrl + targetPath, request.getQueryString());

        RelayViaHop self = new RelayViaHop(head.appKey(), head.name(), head.url());
        List<RelayViaHop> via = viaCodec.append(
                viaCodec.decode(request.getHeader(HEADER_VIA),
                        properties.getRelay().getMaxViaHeaderBytes()),
                self,
                properties.getRelay().getMaxViaHeaderBytes());
        String viaHeader = viaCodec.encode(via, properties.getRelay().getMaxViaHeaderBytes());

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout(request))
                .method(request.getMethod(), bodyPublisher(request))
                .header("X-App-Key", next.appKey())
                .header("X-Timestamp", String.valueOf(next.timestamp()))
                .header("X-Nonce", next.nonce())
                .header("X-Signature", next.signature())
                .header("X-Device-Id", next.deviceId())
                .header(HEADER_VIA, viaHeader)
                // 禁用压缩，避免下游压缩后下载内容与客户端校验的 SHA-256 不一致。
                .header("Accept-Encoding", "identity");
        if (remaining.size() > 1) {
            builder.header(HEADER_CHAIN, chainCodec.encode(remaining));
        }
        copyHeader(request, builder, HEADER_DEVICE_MAC);
        copyHeader(request, builder, HEADER_CONTENT_SHA256);
        copyHeader(request, builder, "Content-Type");
        copyHeader(request, builder, "Accept");

        HttpResponse<InputStream> downstream = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            relayResponse(response, request.getMethod(), downstream);
        } finally {
            closeQuietly(downstream.body());
        }
    }

    /** 校验并归一化「去掉 /api/relay 后的原始路径」：必须以 /api/ 开头且不能含穿越/嵌套 relay。 */
    private String validateOriginalPath(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(PREFIX)) {
            throw invalid("转发路径无效");
        }
        String originalPath = requestUri.substring(PREFIX.length());
        if (!originalPath.startsWith("/api/")) {
            throw invalid("转发路径必须以 /api/ 开头");
        }
        String lower = originalPath.toLowerCase(Locale.ROOT);
        if (originalPath.contains("..")
                || originalPath.contains("//")
                || originalPath.contains("\\")
                || lower.contains("%2e")
                || originalPath.startsWith("/api/relay")) {
            throw invalid("转发路径非法");
        }
        return originalPath;
    }

    /** 链头必须与本次请求的标准签名头完全一致，防止链头/请求错配。 */
    private void verifyHeadMatchesRequest(HttpServletRequest request, RelayHop head) {
        String appKey = (String) request.getAttribute(SignatureFilter.ATTR_APP_KEY);
        String deviceId = (String) request.getAttribute(SignatureFilter.ATTR_DEVICE_ID);
        if (appKey == null || !appKey.equals(head.appKey())) {
            throw invalid("链头 AppKey 与请求不一致");
        }
        if (deviceId == null || deviceId.isBlank() || !deviceId.equals(head.deviceId())) {
            throw invalid("链头设备与请求不一致");
        }
        if (!headerEquals(request, "X-Timestamp", String.valueOf(head.timestamp()))
                || !headerEquals(request, "X-Nonce", head.nonce())
                || !headerEquals(request, "X-Signature", head.signature())) {
            throw invalid("链头签名与请求不一致");
        }
    }

    private static boolean headerEquals(HttpServletRequest request, String name, String expected) {
        String value = request.getHeader(name);
        return value != null && value.equals(expected);
    }

    private URI buildTargetUri(String url, String rawQuery) {
        String candidate = (rawQuery == null || rawQuery.isBlank()) ? url : url + "?" + rawQuery;
        try {
            return URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw invalid("转发目标地址格式无效");
        }
    }

    private void relayResponse(HttpServletResponse response, String method,
                               HttpResponse<InputStream> downstream) throws IOException {
        int status = downstream.statusCode();
        String downstreamRequestId = downstream.headers().firstValue(HEADER_REQUEST_ID).orElse(null);
        if (status >= 400) {
            byte[] body = downstream.body() == null ? new byte[0]
                    : downstream.body().readNBytes(MAX_ERROR_BODY_BYTES);
            DownstreamEnvelope envelope = parseEnvelope(body);
            ErrorCode mapped = errorMapper.map(status, envelope.code());
            if (downstreamRequestId != null && !downstreamRequestId.isBlank()) {
                response.setHeader(HEADER_RELAY_REQUEST_ID, downstreamRequestId);
            }
            if (mapped != null) {
                writeError(response, mapped, mappedErrorMessage(mapped, status, envelope.message()));
                return;
            }
            // 业务 4xx / 设备绑定 / 限流 / 下游 5xx JSON 错误：原样透传状态码与响应体。
            response.setStatus(status);
            String contentType = downstream.headers().firstValue("Content-Type")
                    .orElse(MediaType.APPLICATION_JSON_VALUE);
            response.setContentType(contentType);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
            return;
        }

        // 3xx：禁止跟随重定向，按非法下游响应处理（客户端只允许 http/https 且不跟跳）。
        if (status >= 300) {
            byte[] body = downstream.body() == null ? new byte[0]
                    : downstream.body().readNBytes(MAX_ERROR_BODY_BYTES);
            DownstreamEnvelope envelope = parseEnvelope(body);
            ErrorCode mapped = errorMapper.map(status, envelope.code());
            if (downstreamRequestId != null && !downstreamRequestId.isBlank()) {
                response.setHeader(HEADER_RELAY_REQUEST_ID, downstreamRequestId);
            }
            ErrorCode code = mapped != null ? mapped : ErrorCode.RELAY_TARGET_BAD_RESPONSE;
            writeError(response, code, mappedErrorMessage(code, status, envelope.message()));
            return;
        }

        response.setStatus(status);
        for (String name : RESPONSE_HEADERS) {
            downstream.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
        }
        String contentType = downstream.headers().firstValue("Content-Type")
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentType(contentType);
        if (downstreamRequestId != null && !downstreamRequestId.isBlank()) {
            response.setHeader(HEADER_RELAY_REQUEST_ID, downstreamRequestId);
        }
        if ("HEAD".equalsIgnoreCase(method) || status == HttpServletResponse.SC_NO_CONTENT
                || status == HttpServletResponse.SC_NOT_MODIFIED) {
            return;
        }
        try (InputStream in = downstream.body(); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private record DownstreamEnvelope(Integer code, String message) {
    }

    /** 解析下游错误信封；非 JSON/空 body 返回 (null, null)，由错误映射按状态码兜底。 */
    private DownstreamEnvelope parseEnvelope(byte[] body) {
        if (body == null || body.length == 0) {
            return new DownstreamEnvelope(null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                return new DownstreamEnvelope(null, null);
            }
            Integer code = null;
            JsonNode codeNode = node.get("code");
            if (codeNode != null && codeNode.isNumber()) {
                code = codeNode.asInt();
            }
            String message = null;
            JsonNode messageNode = node.get("message");
            if (messageNode != null && messageNode.isTextual()) {
                message = messageNode.asText();
            }
            return new DownstreamEnvelope(code, message);
        } catch (Exception e) {
            return new DownstreamEnvelope(null, null);
        }
    }

    private static String mappedErrorMessage(ErrorCode mapped, int status, String downstreamMessage) {
        StringBuilder message = new StringBuilder(mapped.getMessage())
                .append("（下游 HTTP ").append(status);
        if (downstreamMessage != null && !downstreamMessage.isBlank()) {
            String trimmed = downstreamMessage.length() > MAX_ERROR_MESSAGE_CHARS
                    ? downstreamMessage.substring(0, MAX_ERROR_MESSAGE_CHARS) + "…"
                    : downstreamMessage;
            message.append("：").append(trimmed);
        }
        return message.append("）").toString();
    }

    private Duration timeout(HttpServletRequest request) {
        String contentType = request.getContentType();
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean upload = request.getRequestURI().contains("/upload")
                || lowerContentType.startsWith("multipart/")
                || lowerContentType.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        int millis = upload
                ? properties.getRelay().getUploadTimeoutMs()
                : properties.getRelay().getRequestTimeoutMs();
        return Duration.ofMillis(millis);
    }

    /**
     * JSON（{@link CachedBodyHttpServletRequest}）用缓存字节重放；multipart/octet-stream
     * 直接桥接原始 InputStream（不缓存整个 body）。已知 Content-Length 时显式声明，
     * 避免把定长上传退化成 chunked。
     */
    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return HttpRequest.BodyPublishers.ofByteArray(cached.getCachedBody());
        }
        long length = request.getContentLengthLong();
        if (length == 0 && request.getHeader("Transfer-Encoding") == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        HttpRequest.BodyPublisher streaming = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return length >= 0
                ? HttpRequest.BodyPublishers.fromPublisher(streaming, length)
                : streaming;
    }

    private void copyHeader(HttpServletRequest request, HttpRequest.Builder builder, String name) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) {
            builder.header(name, value);
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
        response.flushBuffer();
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 忽略关闭失败
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
