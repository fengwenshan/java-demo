package com.wenshan.upload.controller;

import com.wenshan.upload.audit.AuditContext;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.ChunkAckData;
import com.wenshan.upload.dto.ChunkCancelData;
import com.wenshan.upload.dto.ChunkInitData;
import com.wenshan.upload.dto.ChunkInitRequest;
import com.wenshan.upload.dto.ChunkStatusData;
import com.wenshan.upload.dto.ContentSaveData;
import com.wenshan.upload.dto.CopyData;
import com.wenshan.upload.dto.CopyRequest;
import com.wenshan.upload.dto.ContentSaveRequest;
import com.wenshan.upload.dto.DeleteResult;
import com.wenshan.upload.dto.DirListData;
import com.wenshan.upload.dto.FileListData;
import com.wenshan.upload.dto.ExtractData;
import com.wenshan.upload.dto.ExtractRequest;
import com.wenshan.upload.dto.FileContentData;
import com.wenshan.upload.dto.FileMeta;
import com.wenshan.upload.dto.MoveData;
import com.wenshan.upload.dto.MoveRequest;
import com.wenshan.upload.dto.PathDeleteResult;
import com.wenshan.upload.dto.RenameData;
import com.wenshan.upload.dto.RenameRequest;
import com.wenshan.upload.dto.SearchData;
import com.wenshan.upload.security.SignatureFilter;
import com.wenshan.upload.service.ChunkUploadService;
import com.wenshan.upload.service.ContentEditService;
import com.wenshan.upload.service.DirectoryService;
import com.wenshan.upload.service.FileService;
import com.wenshan.upload.service.FileTransferService;
import com.wenshan.upload.service.SearchService;
import com.wenshan.upload.service.ZipExtractService;
import com.wenshan.upload.store.FileRecord;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 契约 3.4 ~ 3.19（FTP 式）：文件列表 / 单文件上传 / 按 id 与按路径下载删除 /
 * 重命名 / 目录浏览 / 分片上传（init/chunk/status/complete/cancel）/ 根目录递归搜索 /
 * 在线编辑文本读写（GET/PUT /content）/ zip 安全解压（POST /extract）。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String SHA256_HEADER = "X-Content-Sha256";
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    private static final String NOSNIFF_VALUE = "nosniff";

    private final FileService fileService;
    private final DirectoryService directoryService;
    private final ChunkUploadService chunkUploadService;
    private final SearchService searchService;
    private final ContentEditService contentEditService;
    private final ZipExtractService zipExtractService;
    private final FileTransferService fileTransferService;

    public FileController(FileService fileService,
                          DirectoryService directoryService,
                          ChunkUploadService chunkUploadService,
                          SearchService searchService,
                          ContentEditService contentEditService,
                          ZipExtractService zipExtractService,
                          FileTransferService fileTransferService) {
        this.fileService = fileService;
        this.directoryService = directoryService;
        this.chunkUploadService = chunkUploadService;
        this.searchService = searchService;
        this.contentEditService = contentEditService;
        this.zipExtractService = zipExtractService;
        this.fileTransferService = fileTransferService;
    }

    @GetMapping
    public ApiResponse<FileListData> list(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ApiResponse.ok(fileService.list(appKey, root, page, size, keyword));
    }

    /**
     * FTP 式上传：可选 {@code root} 指定存储根（缺省=默认根），{@code dir} 相对该根
     * （空/缺省=root，目录必须已存在）；
     * 文件以 name/原始文件名直接落到该目录，重名自动追加 {@code " (n)"}。
     */
    @PostMapping("/upload")
    public ApiResponse<FileMeta> upload(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestAttribute(name = SignatureFilter.ATTR_DEVICE_ID, required = false) String deviceId,
            @RequestAttribute(name = SignatureFilter.ATTR_CONTENT_SHA256, required = false) String contentSha256,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "dir", required = false) String dir,
            HttpServletRequest request) {
        FileMeta meta = fileService.upload(appKey, deviceId, root, file, name, description, dir, contentSha256);
        AuditContext.setFileName(request, meta.name());
        return ApiResponse.ok(meta);
    }

    /**
     * 契约 3.11 分片上传初始化（JSON；签名 payloadHash 为请求体 SHA-256）。
     * 返回 uploadId 与服务端 chunkSize（默认 8MB）。
     */
    @PostMapping("/upload/init")
    public ApiResponse<ChunkInitData> initChunkedUpload(@RequestBody ChunkInitRequest request) {
        return ApiResponse.ok(chunkUploadService.init(request));
    }

    /**
     * 契约 3.12 上传一个分片：body 为 {@code application/octet-stream} 裸字节，
     * {@code X-Content-Sha256} 为该分片 SHA-256 并参与签名（SignatureFilter 不缓存 body）。
     */
    @PutMapping("/upload/{uploadId}/chunk")
    public ApiResponse<ChunkAckData> uploadChunk(
            @PathVariable("uploadId") String uploadId,
            @RequestParam(name = "index") int index,
            @RequestAttribute(name = SignatureFilter.ATTR_CONTENT_SHA256, required = false) String contentSha256,
            HttpServletRequest request) throws IOException {
        return ApiResponse.ok(
                chunkUploadService.writeChunk(uploadId, index, request.getInputStream(), contentSha256));
    }

    /** 契约 3.13 分片上传状态（断点续传）：返回已接收分片 index 列表。 */
    @GetMapping("/upload/{uploadId}")
    public ApiResponse<ChunkStatusData> chunkedUploadStatus(@PathVariable("uploadId") String uploadId) {
        return ApiResponse.ok(chunkUploadService.status(uploadId));
    }

    /** 契约 3.14 完成分片上传：校验分片齐全/总大小/整文件 SHA-256 后落盘并返回 FileMeta。 */
    @PostMapping("/upload/{uploadId}/complete")
    public ApiResponse<FileMeta> completeChunkedUpload(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestAttribute(name = SignatureFilter.ATTR_DEVICE_ID, required = false) String deviceId,
            @PathVariable("uploadId") String uploadId,
            HttpServletRequest request) {
        FileMeta meta = chunkUploadService.complete(appKey, deviceId, uploadId);
        AuditContext.setFileName(request, meta.name());
        return ApiResponse.ok(meta);
    }

    /** 契约 3.15 取消分片上传：删除会话目录。 */
    @DeleteMapping("/upload/{uploadId}")
    public ApiResponse<ChunkCancelData> cancelChunkedUpload(@PathVariable("uploadId") String uploadId) {
        return ApiResponse.ok(chunkUploadService.cancel(uploadId));
    }

    /** 契约 3.16 根目录递归搜索：keyword 必填（1~100），limit 1~500 默认 100。 */
    @GetMapping("/search")
    public ApiResponse<SearchData> search(
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(searchService.search(root, keyword, limit));
    }

    /**
     * 只读浏览指定存储根内的目录（相对该根的 path；省略/空串表示根目录）。
     * 只返回条目元信息，不返回文件内容；严格限制在存储根目录内，内部目录 .upload-meta 隐藏。
     */
    @GetMapping("/dir")
    public ApiResponse<DirListData> listDirectory(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "path", required = false) String path) {
        return ApiResponse.ok(directoryService.listDirectory(root, path));
    }

    /** 按 id 下载（契约 3.6）。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @PathVariable("id") String id) {
        FileService.FileDownload download = fileService.download(appKey, id);
        FileRecord metadata = download.record();
        return streamFile(metadata.name(), metadata.contentType(),
                metadata.size(), metadata.sha256(), download.path());
    }

    /**
     * 浏览视图按路径下载：{@code path} 相对 root；只允许 root 内普通文件
     * （目录/内部目录/越界 → 40000，不存在 → 40400），响应头与按 id 下载一致。
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadByPath(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "path", required = false) String path) {
        FileService.PathDownload download = fileService.downloadByPath(appKey, root, requirePath(path));
        return streamFile(download.fileName(), download.contentType(),
                download.size(), download.sha256(), download.path());
    }

    /** 按 id 删除（契约 3.7）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<DeleteResult> delete(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @PathVariable("id") String id) {
        return ApiResponse.ok(fileService.delete(appKey, id));
    }

    /**
     * 浏览视图按路径删除：{@code path} 相对 root，仅删除普通文件（不删目录/内部目录/越界），
     * 并联动删除元数据中 storedPath 完全相同的记录。
     */
    @DeleteMapping
    public ApiResponse<PathDeleteResult> deleteByPath(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "path", required = false) String path) {
        return ApiResponse.ok(fileService.deleteByPath(appKey, root, requirePath(path)));
    }

    /**
     * 契约 3.17 重命名（JSON；签名 payloadHash 为请求体 SHA-256）：
     * 支持普通文件与目录（目录整体重命名），不改变所在目录；不覆盖已存在目标。
     */
    @PostMapping("/rename")
    public ApiResponse<RenameData> rename(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody RenameRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        return ApiResponse.ok(fileService.rename(appKey, request.root(), request.path(), request.newName()));
    }

    /**
     * 契约 3.18 在线编辑：读取 UTF-8 文本（≤max-edit-bytes），返回当前内容 SHA-256 供保存并发校验。
     */
    @GetMapping("/content")
    public ApiResponse<FileContentData> readContent(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "path", required = false) String path) {
        return ApiResponse.ok(contentEditService.read(appKey, root, requirePath(path)));
    }

    /**
     * 契约 3.18 在线编辑：原子覆盖保存到同一路径（不新建/不改名/不换目录）；
     * expectedSha256 可选，用于检测打开后文件是否被并发修改。
     */
    @PutMapping("/content")
    public ApiResponse<ContentSaveData> saveContent(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody ContentSaveRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        return ApiResponse.ok(contentEditService.save(appKey, request.root(), request.path(),
                request.content(), request.expectedSha256()));
    }

    /**
     * 契约 3.19 zip 安全解压：解压到 zip 同级同名目录（重名追加 {@code (1)}…），
     * 安全校验失败回滚目标目录；解压出的文件不写 files.json。
     */
    @PostMapping("/extract")
    public ApiResponse<ExtractData> extract(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody ExtractRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        return ApiResponse.ok(zipExtractService.extract(appKey, request.root(), request.path()));
    }

    /**
     * 契约 3.22 拷贝（JSON）：在源同父目录生成 {@code _副本}（文件保留扩展名，目录整体追加），
     * 重名依次 {@code _副本 (1)…}；目录递归且不跟随符号链接；不写 files.json。
     */
    @PostMapping("/copy")
    public ApiResponse<CopyData> copy(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody CopyRequest request,
            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        CopyData data = fileTransferService.copy(appKey, request.root(), request.path());
        AuditContext.setFileName(httpRequest, data.name());
        return ApiResponse.ok(data);
    }

    /**
     * 契约 3.23 移动（JSON）：移动到目标根的指定目录（可跨根）；禁止自身/子目录与覆盖同名目标；
     * 同根 Files.move、跨根复制+删除，并同步 files.json 的 storedPath/rootId。
     */
    @PostMapping("/move")
    public ApiResponse<MoveData> move(
            @RequestAttribute(SignatureFilter.ATTR_APP_KEY) String appKey,
            @RequestBody MoveRequest request,
            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        MoveData data = fileTransferService.move(appKey, request.root(), request.path(),
                request.targetRoot(), request.targetDir());
        AuditContext.setFileName(httpRequest, data.name());
        return ApiResponse.ok(data);
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        return path;
    }

    private static ResponseEntity<Resource> streamFile(String fileName, String contentType,
                                                       long size, String sha256, Path path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(fileName));
        headers.set(SHA256_HEADER, sha256);
        headers.set(NOSNIFF_HEADER, NOSNIFF_VALUE);
        headers.setCacheControl(CacheControl.noStore().getHeaderValue());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(resolveContentType(contentType))
                .contentLength(size)
                .body(new FileSystemResource(path));
    }

    private static MediaType resolveContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 契约 3.6：Content-Disposition: attachment; filename="fallback-name"; filename*=UTF-8''...
     */
    static String buildContentDisposition(String fileName) {
        String fallback = asciiFallback(fileName);
        String encoded = rfc5987Encode(fileName);
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String asciiFallback(String fileName) {
        StringBuilder builder = new StringBuilder(fileName.length());
        for (int index = 0; index < fileName.length(); index++) {
            char current = fileName.charAt(index);
            if (current >= 0x20 && current <= 0x7E && current != '"' && current != '\\') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        String fallback = builder.toString().trim();
        return fallback.isEmpty() ? "download" : fallback;
    }

    private static String rfc5987Encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 3);
        for (byte rawByte : bytes) {
            int valueByte = rawByte & 0xFF;
            if (isAttrChar(valueByte)) {
                builder.append((char) valueByte);
            } else {
                builder.append('%');
                builder.append(Character.toUpperCase(Character.forDigit((valueByte >>> 4) & 0x0F, 16)));
                builder.append(Character.toUpperCase(Character.forDigit(valueByte & 0x0F, 16)));
            }
        }
        return builder.toString();
    }

    private static boolean isAttrChar(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '!' || value == '#' || value == '$' || value == '&'
                || value == '+' || value == '-' || value == '.' || value == '^'
                || value == '_' || value == '`' || value == '|' || value == '~';
    }
}
