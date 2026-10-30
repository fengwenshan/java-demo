package com.wenshan.upload;

import com.wenshan.upload.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 防止新增接口漏掉签名校验：动态枚举所有 {@code /api/**} 路由（health 除外），
 * 不带任何签名头调用，必须全部返回 40100。
 */
@SpringBootTest(properties = "app.test.suite=signature-coverage")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureCoverageIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyApiRouteExceptHealthRejectsUnsignedRequests() throws Exception {
        Set<String> covered = new LinkedHashSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            HandlerMethod handler = entry.getValue();
            for (String pattern : mapping.getPatternValues()) {
                if (!pattern.startsWith("/api/") || "/api/health".equals(pattern)) {
                    continue;
                }
                for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                    String url = pattern.replaceAll("\\{[^}]+}", "unsigned-test");
                    covered.add(method.name() + " " + url);
                    mockMvc.perform(request(HttpMethod.valueOf(method.name()), url))
                            .andExpect(status().isUnauthorized())
                            .andExpect(jsonPath("$.code").value(40100));
                }
            }
        }
        // 路由清单至少覆盖当前全部 controller；新增接口不签名会在这里失败。
        assertThat(covered).hasSizeGreaterThanOrEqualTo(24);
    }
}
