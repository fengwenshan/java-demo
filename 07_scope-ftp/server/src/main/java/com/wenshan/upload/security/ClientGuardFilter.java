package com.wenshan.upload.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 最高优先级客户端身份过滤器：在签名/健康检查之前拦截所有 {@code /api/**} 请求。
 *
 * <p>开启 {@code app.security.client-identity-enforced} 后：
 * <ul>
 *   <li>缺少/非法 {@code X-Device-Mac} 或取不到 IP → 写黑名单并 40312；</li>
 *   <li>IP 命中 {@code client_blacklist_ip} → 40310；MAC 命中 {@code client_blacklist_mac} → 40311；</li>
 *   <li>包括 {@code /api/health} 在内的所有接口都处理，没有白名单。</li>
 * </ul>
 * 关闭时只解析身份并写入 request attribute（供 SignatureFilter 使用），不做任何拦截。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientGuardFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final ClientGuardService guardService;
    private final ObjectMapper objectMapper;

    public ClientGuardFilter(ClientGuardService guardService, ObjectMapper objectMapper) {
        this.guardService = guardService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(API_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            ClientGuardService.ClientIdentity identity = guardService.check(request);
            if (identity.ip() != null) {
                request.setAttribute(ClientGuardService.ATTR_CLIENT_IP, identity.ip());
            }
            if (identity.mac() != null) {
                request.setAttribute(ClientGuardService.ATTR_CLIENT_MAC, identity.mac());
            }
            filterChain.doFilter(request, response);
        } catch (SignatureFilter.RejectedException e) {
            writeError(response, e.errorCode);
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode));
    }
}
