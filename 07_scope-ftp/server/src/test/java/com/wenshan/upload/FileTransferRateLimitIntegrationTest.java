package com.wenshan.upload;

import com.wenshan.upload.dto.CopyRequest;
import com.wenshan.upload.dto.MoveRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.22/3.23：拷贝/移动接口未签名 40100；令牌消耗与上传/解压同级（uploadCost=5）。
 * 令牌桶容量 10、不补充，便于「上传 5 + 拷贝 5 → 再操作 42900」精确验证。
 */
@SpringBootTest(properties = {
        "app.test.suite=file-transfer-rate",
        "app.security.rate-limit.capacity=10",
        "app.security.rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileTransferRateLimitIntegrationTest extends IntegrationTestSupport {

    private static final String COPY_PATH = "/api/files/copy";
    private static final String MOVE_PATH = "/api/files/move";
    private static final String UPLOAD_PATH = "/api/files/upload";

    @Test
    void copyAndMoveWithoutSignatureRejected() throws Exception {
        byte[] copyBody = objectMapper.writeValueAsBytes(new CopyRequest(null, "docs/a.txt"));
        mockMvc.perform(post(COPY_PATH).contentType("application/json").content(copyBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        byte[] moveBody = objectMapper.writeValueAsBytes(new MoveRequest(null, "docs/a.txt", null, ""));
        mockMvc.perform(post(MOVE_PATH).contentType("application/json").content(moveBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void copyConsumesUploadCostTokens() throws Exception {
        upload("a.txt");
        byte[] copyBody = objectMapper.writeValueAsBytes(new CopyRequest(null, "docs/a.txt"));
        mockMvc.perform(TestSigner.signedJsonPost(post(COPY_PATH), COPY_PATH, copyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 上传 5 + 拷贝 5 = 10，桶已空；再拷贝 → 42900（说明拷贝消耗 5 而不是 1）
        mockMvc.perform(TestSigner.signedJsonPost(post(COPY_PATH), COPY_PATH, copyBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void moveConsumesUploadCostTokens() throws Exception {
        upload("m.txt");
        Files.createDirectories(storagePaths.root().resolve("docs/dest"));
        byte[] moveBody = objectMapper.writeValueAsBytes(new MoveRequest(null, "docs/m.txt", null, "docs/dest"));
        mockMvc.perform(TestSigner.signedJsonPost(post(MOVE_PATH), MOVE_PATH, moveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 上传 5 + 移动 5 = 10，桶已空；随即拷贝一个根内文件 → 42900
        Files.writeString(storagePaths.root().resolve("docs/b.txt"), "b", StandardCharsets.UTF_8);
        byte[] copyBody = objectMapper.writeValueAsBytes(new CopyRequest(null, "docs/b.txt"));
        mockMvc.perform(TestSigner.signedJsonPost(post(COPY_PATH), COPY_PATH, copyBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value(containsString("频繁")));
    }

    private void upload(String fileName) throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = ("content-" + fileName).getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", fileName);
        builder.param("dir", "docs");
        mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
