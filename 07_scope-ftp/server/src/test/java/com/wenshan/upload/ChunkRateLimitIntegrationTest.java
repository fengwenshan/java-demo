package com.wenshan.upload;

import com.wenshan.upload.dto.ChunkInitRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片接口的令牌消耗（契约第 4 节）：init/complete 消耗 5 个令牌，chunk/status/DELETE 消耗 1 个。
 *
 * <p>通过把令牌桶容量设为 10、不补充，用「第 N 个请求是否 42900」反推消耗量。</p>
 */
@SpringBootTest(properties = {
        "app.test.suite=chunk-rate-limit",
        "app.storage.chunk-size=1024",
        "app.security.rate-limit.capacity=10",
        "app.security.rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkRateLimitIntegrationTest extends IntegrationTestSupport {

    private static final String INIT_PATH = "/api/files/upload/init";
    private static final int CHUNK_SIZE = 1024;

    @Test
    void initAndStatusAndChunksLeaveTooFewTokensForComplete() throws Exception {
        byte[] content = deterministicBytes(1500);
        String uploadId = init(content);

        // init 已消耗 5，剩 5；status 消耗 1，剩 4；两个分片各消耗 1，剩 2
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 1, chunkOf(content, 1)))
                .andExpect(status().isOk());

        // complete 需要 5 个令牌，只剩 2 → 42900（若 complete 只消耗 1 则会成功）
        mockMvc.perform(TestSigner.signedPost("/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value("请求过于频繁"));
    }

    @Test
    void repeatedChunksEachCostOneToken() throws Exception {
        byte[] content = deterministicBytes(1500);
        String uploadId = init(content);
        // init 剩 5；重复上传同一分片 5 次各消耗 1 → 恰好耗尽
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void cancelCostsOneToken() throws Exception {
        byte[] content = deterministicBytes(1500);
        String uploadId = init(content);
        // init 剩 5；cancel 消耗 1 → 剩 4
        mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + uploadId))
                .andExpect(status().isOk());
        // 4 次不存在的会话 DELETE 各消耗 1 → 恰好耗尽
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(40400));
        }
        mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + UUID.randomUUID()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    private String init(byte[] content) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new ChunkInitRequest(
                "rate.bin", null, (long) content.length, TestSigner.sha256(content), null));
        ResultActions result = mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(result).path("data").path("uploadId").asText();
    }

    private static byte[] chunkOf(byte[] content, int index) {
        int from = index * CHUNK_SIZE;
        int to = Math.min(content.length, from + CHUNK_SIZE);
        return Arrays.copyOfRange(content, from, to);
    }

    private static byte[] deterministicBytes(int size) {
        byte[] content = new byte[size];
        for (int index = 0; index < size; index++) {
            content[index] = (byte) ((index * 17 + 3) & 0xFF);
        }
        return content;
    }
}
