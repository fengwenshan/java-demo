package com.wenshan.upload;

import com.wenshan.upload.dto.ChunkInitRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片上传 / 断点续传接口（契约 3.11 ~ 3.15）：
 * init → 乱序/重复 chunk → status → complete；缺片续传；SHA 不符；取消；目录规则；重名。
 *
 * <p>测试上下文把 chunkSize 调小为 1024，便于用小文件覆盖多分片场景；生产默认 8MB。</p>
 */
@SpringBootTest(properties = {
        "app.test.suite=chunked-upload",
        "app.storage.chunk-size=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkedUploadIntegrationTest extends IntegrationTestSupport {

    private static final String INIT_PATH = "/api/files/upload/init";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final int CHUNK_SIZE = 1024;

    @Test
    void initChunkOutOfOrderAndDuplicateThenComplete() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = deterministicBytes(2500);
        String sha256 = TestSigner.sha256(content);

        byte[] initBody = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("报告.pdf", "docs", (long) content.length, sha256, "季度方案"));
        ResultActions initResult = mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, initBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.chunkSize").value(CHUNK_SIZE));
        String uploadId = body(initResult).path("data").path("uploadId").asText();
        assertThat(uploadId).isNotBlank();

        // 初始状态：没有已接收分片
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("报告.pdf"))
                .andExpect(jsonPath("$.data.size").value(content.length))
                .andExpect(jsonPath("$.data.chunkSize").value(CHUNK_SIZE))
                .andExpect(jsonPath("$.data.received", hasSize(0)));

        // 乱序上传：最后一片 → 第一片；重复上传同一片幂等
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 2, chunkOf(content, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(1));
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(2));
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(2));

        // 断点续传状态：received 升序
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received[0]").value(0))
                .andExpect(jsonPath("$.data.received[1]").value(2));

        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 1, chunkOf(content, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(3));

        Path sessionDir = uploadSessionStore.sessionDir(uploadId);
        assertThat(sessionDir).isDirectory();

        ResultActions completeResult = mockMvc.perform(TestSigner.signedPost(
                        "/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("报告.pdf"))
                .andExpect(jsonPath("$.data.description").value("季度方案"))
                .andExpect(jsonPath("$.data.size").value(content.length))
                .andExpect(jsonPath("$.data.sha256").value(sha256))
                .andExpect(jsonPath("$.data.createdAt", endsWith("Z")));
        String id = body(completeResult).path("data").path("id").asText();

        // 落盘位置/内容/元数据 storedPath 正确；会话目录已清理
        assertThat(storedPathOf(id)).isEqualTo("docs/报告.pdf");
        assertThat(storagePaths.root().resolve("docs/报告.pdf")).hasBinaryContent(content);
        assertThat(sessionDir).doesNotExist();

        // 路径式下载校验内容与 SHA 头
        String rawQuery = "path=" + URLEncoder.encode("docs/报告.pdf", StandardCharsets.UTF_8);
        ResultActions download = mockMvc.perform(TestSigner.signedGet(
                        get(DOWNLOAD_PATH).queryParam("path", "docs/报告.pdf"), DOWNLOAD_PATH, rawQuery))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", sha256));
        assertThat(download.andReturn().getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void incompleteCompleteKeepsSessionThenResumeSucceeds() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = deterministicBytes(3000);
        String sha256 = TestSigner.sha256(content);
        String uploadId = init("resume.bin", "docs", content.length, sha256);

        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 2, chunkOf(content, 2)))
                .andExpect(status().isOk());

        // 缺片：40000，且会话保留（可以继续补传）
        mockMvc.perform(TestSigner.signedPost("/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received", hasSize(2)));

        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 1, chunkOf(content, 1)))
                .andExpect(status().isOk());
        ResultActions complete = mockMvc.perform(TestSigner.signedPost(
                        "/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(storedPathOf(body(complete).path("data").path("id").asText()))
                .isEqualTo("docs/resume.bin");
        assertThat(storagePaths.root().resolve("docs/resume.bin")).hasBinaryContent(content);
    }

    @Test
    void completeShaMismatchReturns40001AndCleansSession() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] actual = "actual-chunked-content".getBytes(StandardCharsets.UTF_8);
        byte[] declared = "declared-different-content".getBytes(StandardCharsets.UTF_8);
        String uploadId = init("bad-sha.bin", "docs", actual.length, TestSigner.sha256(declared));

        // 分片本身哈希正确（与声明整文件哈希无关）
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, actual))
                .andExpect(status().isOk());

        mockMvc.perform(TestSigner.signedPost("/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        // 会话与半成品都被清理，root 下不留文件
        assertThat(uploadSessionStore.sessionDir(uploadId)).doesNotExist();
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    void invalidChunkIndexSizeAndHashRejected() throws Exception {
        byte[] content = deterministicBytes(1500);
        String uploadId = init("edge.bin", null, content.length, TestSigner.sha256(content));

        // index 越界 / 负数
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 2, chunkOf(content, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, -1, chunkOf(content, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 单片超过 chunkSize
        byte[] oversized = Arrays.copyOf(content, CHUNK_SIZE + 1);
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 空分片
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 分片内容与 X-Content-Sha256 不一致 → 40001
        mockMvc.perform(TestSigner.signedChunkPutWithDeclaredSha(
                        uploadId, 0, chunkOf(content, 0), TestSigner.sha256("other")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        // 累计大小超过声明的 size：1024 + 1024 > 1500（第二片仍 ≤ chunkSize，但总量超声明值）
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 1, deterministicBytes(CHUNK_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        // 会话仍在，已接收的 index 0 未被破坏
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received", hasSize(1)))
                .andExpect(jsonPath("$.data.received[0]").value(0));

        // 缺少 index 参数 → 40000
        String path = "/api/files/upload/" + uploadId + "/chunk";
        byte[] body = chunkOf(content, 0);
        MockHttpServletRequestBuilder missingIndex = put(path)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body);
        mockMvc.perform(TestSigner.withSignature(missingIndex, "PUT", path, null,
                        TestSigner.sha256(body), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                        TestSigner.now(), TestSigner.newNonce(), null, TestSigner.sha256(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unknownOrInvalidSessionRejected() throws Exception {
        String unknown = UUID.randomUUID().toString();
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + unknown, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedChunkPut(unknown, 0, new byte[]{1, 2, 3}))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedPost("/api/files/upload/" + unknown + "/complete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // 非 UUID 的 uploadId → 40000（防止路径拼接注入）
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/not-a-uuid", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void duplicateCompletedNameGetsNumberedSuffix() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] first = deterministicBytes(1300);
        byte[] second = deterministicBytes(1500);

        String firstId = completeUpload("report.pdf", "docs", first);
        String secondId = completeUpload("report.pdf", "docs", second);

        assertThat(storedPathOf(firstId)).isEqualTo("docs/report.pdf");
        assertThat(storedPathOf(secondId)).isEqualTo("docs/report (1).pdf");
        assertThat(storagePaths.root().resolve("docs/report (1).pdf")).hasBinaryContent(second);

        // 元数据两条记录，展示名都保持原名
        mockMvc.perform(TestSigner.signedGet(get("/api/files")
                        .queryParam("page", "1").queryParam("size", "20").queryParam("keyword", "report"),
                "/api/files", "keyword=report&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void chunkedUploadToRootAndInitValidation() throws Exception {
        byte[] content = deterministicBytes(1200);
        String id = completeUpload("root-file.txt", null, content);
        assertThat(storedPathOf(id)).isEqualTo("root-file.txt");
        assertThat(storagePaths.root().resolve("root-file.txt")).hasBinaryContent(content);

        byte[] initBody = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("a.txt", "missing-dir", 10L, TestSigner.sha256(content), null));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, initBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        for (String badDir : new String[]{"../x", "/etc", ".upload-meta"}) {
            byte[] badBody = objectMapper.writeValueAsBytes(
                    new ChunkInitRequest("a.txt", badDir, 10L, TestSigner.sha256(content), null));
            mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, badBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        Files.writeString(storagePaths.root().resolve("plain.txt"), "not a dir");
        byte[] fileAsDir = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("a.txt", "plain.txt", 10L, TestSigner.sha256(content), null));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, fileAsDir))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // size 校验：0 / 负 / 超过 maxFileSize（测试 profile=1MB）
        for (long badSize : new long[]{0L, -1L}) {
            byte[] badBody = objectMapper.writeValueAsBytes(
                    new ChunkInitRequest("a.txt", null, badSize, TestSigner.sha256(content), null));
            mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, badBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        byte[] tooLarge = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("a.txt", null, 1_048_577L, TestSigner.sha256(content), null));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, tooLarge))
                .andExpect(status().isRequestEntityTooLarge())
                .andExpect(jsonPath("$.code").value(40003));

        // sha256 非 64 hex / name 缺失 / description 超长
        byte[] badSha = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("a.txt", null, 10L, "not-a-sha", null));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, badSha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        byte[] missingName = objectMapper.writeValueAsBytes(
                new ChunkInitRequest(null, null, 10L, TestSigner.sha256(content), null));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, missingName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        byte[] longDescription = objectMapper.writeValueAsBytes(
                new ChunkInitRequest("a.txt", null, 10L, TestSigner.sha256(content), "d".repeat(201)));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, longDescription))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void cancelRemovesSession() throws Exception {
        byte[] content = deterministicBytes(1500);
        String uploadId = init("cancel.bin", null, content.length, TestSigner.sha256(content));
        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, chunkOf(content, 0)))
                .andExpect(status().isOk());
        assertThat(uploadSessionStore.sessionDir(uploadId)).isDirectory();

        mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.uploadId").value(uploadId));
        assertThat(uploadSessionStore.sessionDir(uploadId)).doesNotExist();
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        // 已取消的会话再次取消 → 40400
        mockMvc.perform(TestSigner.signedDelete("/api/files/upload/" + uploadId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    void octetStreamWithoutContentShaRejectedWith40100() throws Exception {
        String path = "/api/files/upload/" + UUID.randomUUID() + "/chunk";
        byte[] body = deterministicBytes(64);
        MockHttpServletRequestBuilder builder = put(path)
                .queryParam("index", "0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body);
        mockMvc.perform(TestSigner.withSignature(builder, "PUT", path, "index=0",
                        TestSigner.sha256(body), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                        TestSigner.now(), TestSigner.newNonce(), null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /**
     * 回归：application/octet-stream 请求体不再走 JSON 的 1MB 缓存上限。
     * 2MB 分片（超过 JSON 缓存上限）签名正确时过滤器应放行，最终由业务返回 40400（会话不存在），
     * 而不是 40000/40100。
     */
    @Test
    void largeOctetStreamBodyIsNotCachedByFilter() throws Exception {
        String unknown = UUID.randomUUID().toString();
        byte[] largeChunk = deterministicBytes(2 * 1024 * 1024);
        mockMvc.perform(TestSigner.signedChunkPut(unknown, 0, largeChunk))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ------------------------------------------------------------------

    private String init(String name, String dir, long size, String sha256) throws Exception {
        byte[] initBody = objectMapper.writeValueAsBytes(
                new ChunkInitRequest(name, dir, size, sha256, null));
        ResultActions result = mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, initBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(result).path("data").path("uploadId").asText();
    }

    private String completeUpload(String name, String dir, byte[] content) throws Exception {
        String uploadId = init(name, dir, content.length, TestSigner.sha256(content));
        int totalChunks = (content.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int index = 0; index < totalChunks; index++) {
            mockMvc.perform(TestSigner.signedChunkPut(uploadId, index, chunkOf(content, index)))
                    .andExpect(status().isOk());
        }
        ResultActions complete = mockMvc.perform(TestSigner.signedPost(
                        "/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(complete).path("data").path("id").asText();
    }

    private static byte[] chunkOf(byte[] content, int index) {
        int from = index * CHUNK_SIZE;
        int to = Math.min(content.length, from + CHUNK_SIZE);
        return Arrays.copyOfRange(content, from, to);
    }

    private static byte[] deterministicBytes(int size) {
        byte[] content = new byte[size];
        for (int index = 0; index < size; index++) {
            content[index] = (byte) ((index * 31 + 7) & 0xFF);
        }
        return content;
    }
}
