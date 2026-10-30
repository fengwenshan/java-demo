package com.wenshan.upload.controller;

import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.dto.HealthData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * 契约 3.1 健康检查（无需签名）。
 */
@RestController
public class HealthController {

    private final Clock clock;

    public HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/health")
    public ApiResponse<HealthData> health() {
        return ApiResponse.ok(new HealthData("UP", clock.millis()));
    }
}
