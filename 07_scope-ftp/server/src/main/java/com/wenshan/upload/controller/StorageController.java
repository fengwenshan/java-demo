package com.wenshan.upload.controller;

import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.dto.StorageRootsData;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储根目录信息接口（签名接口，令牌消耗 1）。
 *
 * <p>{@code GET /api/storage/roots} → {@code {defaultRootId, roots:[{id,name,path}]}}；
 * path 为服务端解析后的绝对路径，供客户端展示与用户确认。客户端所有文件接口的 {@code root}
 * 参数必须是这里返回的 id 之一（缺省用 defaultRootId）。</p>
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final StorageRootRegistry rootRegistry;

    public StorageController(StorageRootRegistry rootRegistry) {
        this.rootRegistry = rootRegistry;
    }

    @GetMapping("/roots")
    public ApiResponse<StorageRootsData> roots() {
        return ApiResponse.ok(new StorageRootsData(rootRegistry.defaultRootId(), rootRegistry.infos()));
    }
}
