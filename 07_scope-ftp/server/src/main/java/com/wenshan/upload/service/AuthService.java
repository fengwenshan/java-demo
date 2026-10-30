package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.ActivateData;
import com.wenshan.upload.dto.DeviceInfo;
import com.wenshan.upload.dto.Limits;
import com.wenshan.upload.dto.VerifyData;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.DeviceRecord;
import com.wenshan.upload.store.MetaStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端凭证与设备绑定（元数据落 JSON 文件，见 {@link MetaStore}）。
 *
 * <p>设备绑定是全局（与根无关）的，统一存放在默认根的 {@code .upload-meta/devices.json}；
 * verify 同时返回全部存储根与默认根，供客户端选择 root。</p>
 */
@Service
public class AuthService {

    private final AppProperties properties;
    private final StorageRootRegistry rootRegistry;
    private final Clock clock;
    private final Map<String, AppProperties.Client> clientsByKey = new HashMap<>();

    public AuthService(AppProperties properties, StorageRootRegistry rootRegistry, Clock clock) {
        this.properties = properties;
        this.rootRegistry = rootRegistry;
        this.clock = clock;
        for (AppProperties.Client client : properties.getSecurity().getClients()) {
            if (client.getAppKey() != null && !client.getAppKey().isBlank()) {
                clientsByKey.put(client.getAppKey(), client);
            }
        }
    }

    /** 查找启用状态下的客户端配置；不存在或禁用返回 null。 */
    public AppProperties.Client findEnabledClient(String appKey) {
        if (appKey == null) {
            return null;
        }
        AppProperties.Client client = clientsByKey.get(appKey);
        if (client == null || !client.isEnabled()) {
            return null;
        }
        return client;
    }

    /**
     * 契约 2.6 第 6 步：开启设备绑定时，已绑定的 appKey 只允许绑定过的 deviceId 调用。
     * 未绑定时不限制。
     */
    public boolean isDeviceAllowed(String appKey, String deviceId) {
        return defaultMetaStore().findDevice(appKey)
                .map(record -> record.deviceId().equals(deviceId))
                .orElse(true);
    }

    /** 契约 3.2。 */
    public VerifyData verify(String appKey) {
        AppProperties.Client client = findEnabledClient(appKey);
        if (client == null) {
            throw new BizException(ErrorCode.CLIENT_UNAUTHORIZED);
        }
        AppProperties.Storage storage = properties.getStorage();
        DeviceInfo deviceInfo = defaultMetaStore().findDevice(appKey)
                .map(record -> new DeviceInfo(record.deviceId(), record.deviceName(), record.createdAt()))
                .orElse(null);
        StorageRoot defaultRoot = rootRegistry.defaultRoot();
        return new VerifyData(
                appKey,
                client.getName(),
                clock.millis(),
                properties.getSecurity().isDeviceBindingEnabled(),
                deviceInfo,
                new Limits(storage.getMaxFileSize()),
                defaultRoot.path().toString(),
                rootRegistry.infos(),
                rootRegistry.defaultRootId());
    }

    /** 契约 3.3。 */
    public ActivateData activate(String appKey, String deviceId, String deviceName) {
        AppProperties.Client client = findEnabledClient(appKey);
        if (client == null) {
            throw new BizException(ErrorCode.CLIENT_UNAUTHORIZED);
        }
        Instant now = Instant.ofEpochMilli(clock.millis()).truncatedTo(ChronoUnit.SECONDS);
        DeviceRecord existing = defaultMetaStore().findDevice(appKey).orElse(null);
        DeviceRecord updated;
        if (existing == null) {
            updated = new DeviceRecord(appKey, deviceId, deviceName, now, now);
        } else if (existing.deviceId().equals(deviceId)) {
            updated = new DeviceRecord(appKey, deviceId, deviceName, existing.createdAt(), now);
        } else if (properties.getSecurity().isDeviceBindingEnabled()) {
            throw new BizException(ErrorCode.DEVICE_UNAUTHORIZED);
        } else {
            updated = new DeviceRecord(appKey, deviceId, deviceName, now, now);
        }
        defaultMetaStore().saveDevice(updated);
        return new ActivateData(appKey, updated.deviceId(), updated.deviceName(),
                updated.createdAt(), clock.millis());
    }

    /** 设备绑定与旧单根行为一致，落在默认根的元数据目录。 */
    private MetaStore defaultMetaStore() {
        return rootRegistry.defaultRoot().metaStore();
    }
}
