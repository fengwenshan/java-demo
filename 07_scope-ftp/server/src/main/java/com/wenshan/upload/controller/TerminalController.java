package com.wenshan.upload.controller;

import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.TerminalExecData;
import com.wenshan.upload.dto.TerminalExecRequest;
import com.wenshan.upload.security.SignatureFilter;
import com.wenshan.upload.service.TerminalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约 3.21 远程服务器终端：签名 JSON 接口，令牌消耗 5。
 *
 * <p>高危命令由 {@link com.wenshan.upload.terminal.TerminalGuard} 拦截（40000 + 中文原因，不启动进程）；
 * 命令非零退出仍返回 code=0 + exitCode。可通过 {@code app.terminal.enabled=false} 整体关闭。</p>
 */
@RestController
@RequestMapping("/api/terminal")
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @PostMapping("/exec")
    public ApiResponse<TerminalExecData> exec(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody TerminalExecRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        return ApiResponse.ok(terminalService.exec(appKey, request.sessionId(),
                request.command(), request.cwd(), request.root()));
    }
}
