package com.wenshan.upload.controller;

import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.dto.ActivateData;
import com.wenshan.upload.dto.ActivateRequest;
import com.wenshan.upload.dto.VerifyData;
import com.wenshan.upload.security.SignatureFilter;
import com.wenshan.upload.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约 3.2 / 3.3：连接校验与设备激活。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/verify")
    public ApiResponse<VerifyData> verify(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey) {
        return ApiResponse.ok(authService.verify(appKey));
    }

    @PostMapping("/activate")
    public ApiResponse<ActivateData> activate(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @Valid @RequestBody ActivateRequest request) {
        return ApiResponse.ok(authService.activate(appKey, request.deviceId(), request.deviceName()));
    }
}
