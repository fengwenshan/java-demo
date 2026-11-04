# ScopeFTP 多级转发（A→B→C）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让客户端能通过任意层级的转发服务器访问目标服务器（A → B → C → …），同时保留每台服务器自己的 AppKey/AppSecret、设备身份和审计来源，并允许客户端删到空配置。

**Architecture:** 客户端保存一棵 `servers[]` 树（`kind=direct|relay`、`parentId`），请求时从根到目标构造“每跳签名”链；最外层请求到 A 的 `/api/relay` + 原 path，A 服务端 `RelayForwardFilter` 弹出链头、校验剩余链、追加 `X-Relay-Via` 后流式转发给下一跳。每一跳只用自己的 secret 验签，secret 不出客户端。来源链以 `{appKey,name,url}` 形式记录到审计，客户端按 appKey 映射到本地服务器并支持点击切换。

**Tech Stack:** Spring Boot 3.3.5 / Java 21 / JUnit 5 / SQLite；Tauri 2 / Rust / reqwest / serde；React 18 / TypeScript / Vite 6 / Ant Design 5。

---

## 执行前必读

- 设计文档：`07_scope-ftp/docs/superpowers/specs/2026-11-03-scope-ftp-relay-chain-design.md`。
- 所有命令从 `07_scope-ftp/` 执行。
- 服务端测试：`./scripts/mvn.sh -f server/pom.xml test`；单个测试：`./scripts/mvn.sh -f server/pom.xml -Dtest=RelayChainCodecTest test`。
- 客户端 Rust 测试：`cd client/src-tauri && cargo test`；前端构建：`cd client && pnpm build`。
- 每个 Task 结束提交一次，提交信息用 `feat(scope-ftp): <本次改动>` 或 `test(scope-ftp): <测试说明>`。
- 不要提交 `data/`、`logs/`、`release/`、`client/src-tauri/target/`。
- 不把任何真实 AppSecret 写进测试、日志或文档；测试密钥只用 `TestSigner` 中的假值。

---

## Task 1: 服务端错误码与 relay 配置

**Files:**
- Modify: `server/src/main/java/com/wenshan/upload/common/ErrorCode.java`
- Modify: `server/src/main/java/com/wenshan/upload/config/AppProperties.java`
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayPropertiesTest.java`

- [ ] **Step 1: 写失败测试**

创建 `server/src/test/java/com/wenshan/upload/relay/RelayPropertiesTest.java`：

```java
package com.wenshan.upload.relay;

import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelayPropertiesTest {

    @Test
    void defaultsAreSafeAndConservative() {
        AppProperties properties = new AppProperties();
        AppProperties.Relay relay = properties.getRelay();

        assertThat(relay.isEnabled()).isFalse();
        assertThat(relay.getMaxHops()).isEqualTo(16);
        assertThat(relay.getMaxChainHeaderBytes()).isEqualTo(16 * 1024);
        assertThat(relay.getMaxViaHeaderBytes()).isEqualTo(8 * 1024);
        assertThat(relay.getConnectTimeoutMs()).isEqualTo(3_000);
        assertThat(relay.getRequestTimeoutMs()).isEqualTo(20_000);
        assertThat(relay.getUploadTimeoutMs()).isEqualTo(180_000);
        assertThat(relay.isAllowPrivateNetwork()).isTrue();
        assertThat(relay.isAllowLoopback()).isFalse();
        assertThat(relay.getAllowedHosts()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayPropertiesTest test`

Expected: 编译失败，`AppProperties.Relay` / `getRelay()` 不存在。

- [ ] **Step 3: 加错误码**

在 `ErrorCode` 的 `NOT_FOUND` 前插入：

```java
    RELAY_CHAIN_INVALID(40010, 400, "转发链无效"),
    RELAY_DISABLED(40320, 403, "转发功能未开启"),
    RELAY_TARGET_UNAUTHORIZED(40110, 401, "转发目标鉴权失败"),
    RELAY_TARGET_UNREACHABLE(50210, 502, "转发目标不可达"),
    RELAY_TARGET_BAD_RESPONSE(50211, 502, "转发目标返回异常"),
    RELAY_TARGET_IDENTITY_REJECTED(50212, 502, "转发目标拒绝来源身份"),
    RELAY_TIMEOUT(50410, 504, "转发目标超时"),
```

- [ ] **Step 4: 加 `AppProperties.Relay`**

在 `AppProperties` 字段区加：

```java
    private final Relay relay = new Relay();
```

在 `getAudit()` 后加：

```java
    public Relay getRelay() {
        return relay;
    }
```

在 `AppProperties` 内部类区（`Audit` 之后）加：

```java
    public static class Relay {

        /** 是否开启 /api/relay；默认关闭，部署时显式开启。 */
        private boolean enabled = false;

        /** 链最多多少跳（含根），默认 16。 */
        private int maxHops = 16;

        /** X-Relay-Chain 原始 header 最大字节数。 */
        private int maxChainHeaderBytes = 16 * 1024;

        /** X-Relay-Via 原始 header 最大字节数。 */
        private int maxViaHeaderBytes = 8 * 1024;

        private int connectTimeoutMs = 3_000;

        /** 普通 JSON 请求超时。 */
        private int requestTimeoutMs = 20_000;

        /** 上传/下载等流式请求超时。 */
        private int uploadTimeoutMs = 180_000;

        /** 是否允许转发到 RFC1918 私网地址。 */
        private boolean allowPrivateNetwork = true;

        /** 是否允许 loopback；默认拒绝，避免 SSRF。 */
        private boolean allowLoopback = false;

        /** 非空时只允许这些 host（逗号分隔，精确匹配 host 或 host:port）。 */
        private List<String> allowedHosts = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxHops() {
            return maxHops;
        }

        public void setMaxHops(int maxHops) {
            this.maxHops = maxHops;
        }

        public int getMaxChainHeaderBytes() {
            return maxChainHeaderBytes;
        }

        public void setMaxChainHeaderBytes(int maxChainHeaderBytes) {
            this.maxChainHeaderBytes = maxChainHeaderBytes;
        }

        public int getMaxViaHeaderBytes() {
            return maxViaHeaderBytes;
        }

        public void setMaxViaHeaderBytes(int maxViaHeaderBytes) {
            this.maxViaHeaderBytes = maxViaHeaderBytes;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public int getUploadTimeoutMs() {
            return uploadTimeoutMs;
        }

        public void setUploadTimeoutMs(int uploadTimeoutMs) {
            this.uploadTimeoutMs = uploadTimeoutMs;
        }

        public boolean isAllowPrivateNetwork() {
            return allowPrivateNetwork;
        }

        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
            this.allowPrivateNetwork = allowPrivateNetwork;
        }

        public boolean isAllowLoopback() {
            return allowLoopback;
        }

        public void setAllowLoopback(boolean allowLoopback) {
            this.allowLoopback = allowLoopback;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }
    }
```

- [ ] **Step 5: 在 `application.yml` 加默认配置**

在 `app:` 下追加：

```yaml
  relay:
    enabled: false
    max-hops: 16
    max-chain-header-bytes: 16384
    max-via-header-bytes: 8192
    connect-timeout-ms: 3000
    request-timeout-ms: 20000
    upload-timeout-ms: 180000
    allow-private-network: true
    allow-loopback: false
    allowed-hosts: []
```

- [ ] **Step 6: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayPropertiesTest test`

Expected: `BUILD SUCCESS`，1 个测试通过。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/common/ErrorCode.java \
        server/src/main/java/com/wenshan/upload/config/AppProperties.java \
        server/src/main/resources/application.yml \
        server/src/test/java/com/wenshan/upload/relay/RelayPropertiesTest.java
git commit -m "feat(scope-ftp): add relay error codes and config"
```

---

## Task 2: 转发链与来源链编解码

**Files:**
- Create: `server/src/main/java/com/wenshan/upload/relay/RelayHop.java`
- Create: `server/src/main/java/com/wenshan/upload/relay/RelayChainCodec.java`
- Create: `server/src/main/java/com/wenshan/upload/audit/RelayViaHop.java`
- Create: `server/src/main/java/com/wenshan/upload/audit/RelayViaCodec.java`
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayChainCodecTest.java`
- Test: `server/src/test/java/com/wenshan/upload/audit/RelayViaCodecTest.java`

- [ ] **Step 1: 写失败测试**

`RelayChainCodecTest.java`：

```java
package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayChainCodecTest {

    private final RelayChainCodec codec = new RelayChainCodec(new ObjectMapper());

    private RelayHop hop(String name) {
        return new RelayHop(
                "http://10.0.0." + name.charAt(0) + ":8080",
                name,
                "app-" + name,
                System.currentTimeMillis(),
                "nonce-" + name,
                "sig-" + name,
                "device-" + name);
    }

    @Test
    void roundTripsTwoHops() {
        List<RelayHop> hops = List.of(hop("A"), hop("B"));
        String header = codec.encode(hops);

        assertThat(header).doesNotContain("=");
        assertThat(codec.decode(header, 16, 16 * 1024)).isEqualTo(hops);
    }

    @Test
    void rejectsMalformedBase64() {
        assertThatThrownBy(() -> codec.decode("not-base64-$$$", 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsEmptyChain() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("[]".getBytes());
        assertThatThrownBy(() -> codec.decode(header, 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsTooManyHops() {
        String header = codec.encode(List.of(hop("A"), hop("B"), hop("C")));
        assertThatThrownBy(() -> codec.decode(header, 2, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsMissingField() {
        String json = "[{\"url\":\"http://10.0.0.5:8080\",\"name\":\"A\"}]";
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes());
        assertThatThrownBy(() -> codec.decode(header, 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsOversizedHeader() {
        String header = codec.encode(List.of(hop("A"), hop("B")));
        assertThatThrownBy(() -> codec.decode(header, 16, 8))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }
}
```

`RelayViaCodecTest.java`：

```java
package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayViaCodecTest {

    private final RelayViaCodec codec = new RelayViaCodec(new ObjectMapper());

    @Test
    void blankMeansEmpty() {
        assertThat(codec.decode(null, 8192)).isEmpty();
        assertThat(codec.decode("  ", 8192)).isEmpty();
    }

    @Test
    void appendAndRoundTrip() {
        List<RelayViaHop> via = codec.append(
                List.of(new RelayViaHop("a", "A", "http://10.0.0.1:8080")),
                new RelayViaHop("b", "B", "http://10.0.0.2:8080"),
                8192);
        assertThat(via).extracting(RelayViaHop::name).containsExactly("A", "B");
        assertThat(codec.decode(codec.encode(via), 8192)).isEqualTo(via);
    }

    @Test
    void rejectsTooLarge() {
        List<RelayViaHop> via = List.of(
                new RelayViaHop("a", "A", "http://10.0.0.1:8080"),
                new RelayViaHop("b", "B", "http://10.0.0.2:8080"));
        assertThatThrownBy(() -> codec.encode(via, 8))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayChainCodecTest,RelayViaCodecTest test`

Expected: 编译失败，类不存在。

- [ ] **Step 3: 实现记录与编解码**

`RelayHop.java`：

```java
package com.wenshan.upload.relay;

/**
 * 链中的一跳。字段名与客户端 JSON 一致，不能改名。
 *
 * <p>链里只有签名，没有 AppSecret；每一跳的 secret 只保存在客户端。</p>
 */
public record RelayHop(
        String url,
        String name,
        String appKey,
        long timestamp,
        String nonce,
        String signature,
        String deviceId) {
}
```

`RelayViaHop.java`：

```java
package com.wenshan.upload.audit;

/**
 * 审计里的一条来源。appKey 用于客户端映射到本地服务器，url 作为兜底匹配。
 */
public record RelayViaHop(String appKey, String name, String url) {
}
```

`RelayChainCodec.java`：

```java
package com.wenshan.upload.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

@Component
public class RelayChainCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public RelayChainCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RelayHop> decode(String header, int maxHops, int maxBytes) {
        if (header == null || header.isBlank()) {
            throw invalid("缺少转发链");
        }
        if (header.length() > maxBytes) {
            throw invalid("转发链过长");
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(header);
        } catch (IllegalArgumentException e) {
            throw invalid("转发链不是合法的 Base64URL");
        }
        if (bytes.length > maxBytes) {
            throw invalid("转发链过长");
        }
        List<RelayHop> hops;
        try {
            hops = objectMapper.readValue(bytes, new TypeReference<List<RelayHop>>() {
            });
        } catch (Exception e) {
            throw invalid("转发链 JSON 无效");
        }
        if (hops == null || hops.isEmpty()) {
            throw invalid("转发链为空");
        }
        if (hops.size() > maxHops) {
            throw invalid("转发链超过最大跳数");
        }
        for (RelayHop hop : hops) {
            validate(hop);
        }
        return List.copyOf(hops);
    }

    public String encode(List<RelayHop> hops) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(hops);
            return ENCODER.encodeToString(bytes);
        } catch (Exception e) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "转发链编码失败");
        }
    }

    private void validate(RelayHop hop) {
        if (hop == null) {
            throw invalid("转发链包含空跳");
        }
        require(hop.url(), 2048, "url");
        require(hop.name(), 64, "name");
        require(hop.appKey(), 128, "appKey");
        require(hop.nonce(), 128, "nonce");
        require(hop.signature(), 256, "signature");
        require(hop.deviceId(), 128, "deviceId");
        if (hop.timestamp() <= 0) {
            throw invalid("转发链 timestamp 无效");
        }
    }

    private void require(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid("转发链 " + field + " 无效");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
```

`RelayViaCodec.java`：

```java
package com.wenshan.upload.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class RelayViaCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public RelayViaCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RelayViaHop> decode(String header, int maxBytes) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        if (header.length() > maxBytes) {
            throw invalid("来源链过长");
        }
        try {
            byte[] bytes = DECODER.decode(header);
            if (bytes.length > maxBytes) {
                throw invalid("来源链过长");
            }
            List<RelayViaHop> hops = objectMapper.readValue(bytes, new TypeReference<List<RelayViaHop>>() {
            });
            return hops == null ? List.of() : List.copyOf(hops);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("来源链无效");
        }
    }

    public List<RelayViaHop> append(List<RelayViaHop> existing, RelayViaHop hop, int maxBytes) {
        List<RelayViaHop> next = new ArrayList<>(existing);
        next.add(hop);
        encode(next, maxBytes);
        return List.copyOf(next);
    }

    public String encode(List<RelayViaHop> hops) {
        return encode(hops, Integer.MAX_VALUE);
    }

    public String encode(List<RelayViaHop> hops, int maxBytes) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(hops);
            String header = ENCODER.encodeToString(bytes);
            if (header.length() > maxBytes) {
                throw invalid("来源链过长");
            }
            return header;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("来源链编码失败");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayChainCodecTest,RelayViaCodecTest test`

Expected: 2 个测试类全部通过。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/relay/RelayHop.java \
        server/src/main/java/com/wenshan/upload/relay/RelayChainCodec.java \
        server/src/main/java/com/wenshan/upload/audit/RelayViaHop.java \
        server/src/main/java/com/wenshan/upload/audit/RelayViaCodec.java \
        server/src/test/java/com/wenshan/upload/relay/RelayChainCodecTest.java \
        server/src/test/java/com/wenshan/upload/audit/RelayViaCodecTest.java
git commit -m "feat(scope-ftp): add relay chain and provenance codecs"
```

---

## Task 3: 转发目标 URL 与 SSRF 守卫

**Files:**
- Create: `server/src/main/java/com/wenshan/upload/relay/RelayUrlGuard.java`
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayUrlGuardTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.wenshan.upload.relay;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayUrlGuardTest {

    private final AppProperties properties = new AppProperties();
    private final RelayUrlGuard guard = new RelayUrlGuard(properties);

    @Test
    void acceptsPrivateHttpUrl() {
        assertThat(guard.validate("http://10.0.0.5:8080/scopeftp").toString())
                .isEqualTo("http://10.0.0.5:8080/scopeftp");
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsUserInfoAndQueryAndFragment() {
        assertThatThrownBy(() -> guard.validate("http://user:pass@10.0.0.5:8080"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:8080?a=1"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:8080#x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsLoopbackByDefault() {
        assertThatThrownBy(() -> guard.validate("http://127.0.0.1:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyEnabled() {
        properties.getRelay().setAllowLoopback(true);
        assertThat(guard.validate("http://127.0.0.1:8080")).hasToString("http://127.0.0.1:8080");
    }

    @Test
    void allowedHostsIsAnAllowlistWhenConfigured() {
        properties.getRelay().setAllowedHosts(java.util.List.of("10.0.0.5"));
        assertThat(guard.validate("http://10.0.0.5:8080")).hasToString("http://10.0.0.5:8080");
        assertThatThrownBy(() -> guard.validate("http://10.0.0.6:8080"))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayUrlGuardTest test`

Expected: 编译失败，`RelayUrlGuard` 不存在。

- [ ] **Step 3: 实现守卫**

```java
package com.wenshan.upload.relay;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

@Component
public class RelayUrlGuard {

    private final AppProperties properties;

    public RelayUrlGuard(AppProperties properties) {
        this.properties = properties;
    }

    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("转发目标地址为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("转发目标地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("转发目标只允许 http/https");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("转发目标地址不能包含用户信息、查询参数或片段");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("转发目标地址缺少主机名");
        }
        if (!allowedHost(host, uri.getPort())) {
            throw invalid("转发目标不在 allowed-hosts 中");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new BizException(ErrorCode.RELAY_TARGET_UNREACHABLE, "无法解析转发目标地址");
        }
        for (InetAddress address : addresses) {
            checkAddress(address);
        }
        return uri;
    }

    private boolean allowedHost(String host, int port) {
        List<String> allowed = properties.getRelay().getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        String withPort = port < 0 ? normalized : normalized + ":" + port;
        return allowed.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals(normalized) || value.equals(withPort));
    }

    private void checkAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw invalid("转发目标地址不允许");
        }
        if (address.isLoopbackAddress() && !properties.getRelay().isAllowLoopback()) {
            throw invalid("转发目标不允许 loopback");
        }
        if (address.isLinkLocalAddress()) {
            throw invalid("转发目标不允许 link-local/元数据地址");
        }
        if (address.isSiteLocalAddress() && !properties.getRelay().isAllowPrivateNetwork()) {
            throw invalid("转发目标不允许私网地址");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayUrlGuardTest test`

Expected: 6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/relay/RelayUrlGuard.java \
        server/src/test/java/com/wenshan/upload/relay/RelayUrlGuardTest.java
git commit -m "feat(scope-ftp): add relay URL SSRF guard"
```

---

## Task 4: 下游错误映射

**Files:**
- Create: `server/src/main/java/com/wenshan/upload/relay/RelayErrorMapper.java`
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayErrorMapperTest.java`

添加失败测试：

```java
package com.wenshan.upload.relay;

import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelayErrorMapperTest {

    private final RelayErrorMapper mapper = new RelayErrorMapper();

    @Test
    void mapsAuthAndPermissionFailures() {
        assertThat(mapper.map(401, null)).isEqualTo(ErrorCode.RELAY_TARGET_UNAUTHORIZED);
        assertThat(mapper.map(403, null)).isEqualTo(ErrorCode.RELAY_TARGET_IDENTITY_REJECTED);
    }

    @Test
    void mapsRateLimitRedirectAndServerErrors() {
        assertThat(mapper.map(302, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(429, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(500, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(502, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
    }

    @Test
    void leavesBusiness4xxToPassThrough() {
        assertThat(mapper.map(400, 40000)).isNull();
        assertThat(mapper.map(404, 40400)).isNull();
        assertThat(mapper.map(413, 40003)).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayErrorMapperTest test`

Expected: 编译失败，`RelayErrorMapper` 不存在。

- [ ] **Step 3: 实现映射**

```java
package com.wenshan.upload.relay;

import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 只映射“本跳要改写成统一 relay 错误码”的下游状态；业务 4xx 原样透传。
 */
@Component
public class RelayErrorMapper {

    public ErrorCode map(int status, Integer downstreamCode) {
        if (status == 401) {
            return ErrorCode.RELAY_TARGET_UNAUTHORIZED;
        }
        if (status == 403) {
            return ErrorCode.RELAY_TARGET_IDENTITY_REJECTED;
        }
        if ((status >= 300 && status < 400) || status == 429 || status >= 500) {
            return ErrorCode.RELAY_TARGET_BAD_RESPONSE;
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayErrorMapperTest test`

Expected: 3 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/relay/RelayErrorMapper.java \
        server/src/test/java/com/wenshan/upload/relay/RelayErrorMapperTest.java
git commit -m "feat(scope-ftp): map downstream relay errors"
```

---

## Task 5: `RelayForwardFilter`（JSON 转发 + 错误 + 来源头）

**Files:**
- Create: `server/src/main/java/com/wenshan/upload/relay/RelayForwardFilter.java`
- Modify: `server/src/main/java/com/wenshan/upload/config/AppConfig.java`（新增 `relayHttpClient` Bean）
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayForwardIntegrationTest.java`

- [ ] **Step 1: 写失败测试（真实端口 + 假 B）**

```java
package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.relay.enabled=true",
                "app.relay.allow-loopback=true",
                "app.security.client-identity-enforced=false"
        })
class RelayForwardIntegrationTest {

    @LocalServerPort
    int port;

    private HttpServer downstream;
    private String downstreamUrl;
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    @BeforeEach
    void startDownstream() throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/api/files", exchange -> {
            seen.put("method", exchange.getRequestMethod());
            seen.put("uri", exchange.getRequestURI().toString());
            seen.put("appKey", exchange.getRequestHeaders().getFirst("X-App-Key"));
            seen.put("via", exchange.getRequestHeaders().getFirst("X-Relay-Via"));
            byte[] body = "{\"code\":0,\"message\":\"ok\",\"data\":{\"from\":\"b\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        downstream.start();
        downstreamUrl = "http://127.0.0.1:" + downstream.getAddress().getPort();
    }

    @AfterEach
    void stopDownstream() {
        downstream.stop(0);
    }

    @Test
    void forwardsJsonGetAndAppendsVia() throws Exception {
        String path = "/api/relay/api/files";
        String query = "keyword=abc";
        String chain = chainHeader("B", "b-app", "b-device");
        HttpResponse<String> response = sendSignedGet(path, query, chain);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"from\":\"b\"");
        assertThat(seen.get("method")).isEqualTo("GET");
        assertThat(seen.get("uri")).isEqualTo("/api/files?keyword=abc");
        assertThat(seen.get("appKey")).isEqualTo("b-app");
        assertThat(seen.get("via")).isNotBlank();
        String viaJson = new String(Base64.getUrlDecoder().decode(seen.get("via")),
                StandardCharsets.UTF_8);
        assertThat(viaJson).contains("\"name\":\"A\"").contains("\"url\":\"" + "http://127.0.0.1:" + port + "\"");
    }

    private String chainHeader(String name, String appKey, String deviceId) throws Exception {
        RelayHop a = new RelayHop(
                "http://127.0.0.1:" + port,
                "A",
                TestSigner.APP_KEY,
                System.currentTimeMillis(),
                TestSigner.newNonce(),
                "ignored",
                "a-device");
        RelayHop b = new RelayHop(
                downstreamUrl,
                name,
                appKey,
                System.currentTimeMillis(),
                TestSigner.newNonce(),
                "ignored",
                deviceId);
        String json = new ObjectMapper().writeValueAsString(List.of(a, b));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendSignedGet(String path, String query, String chain) throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String canonical = TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce,
                "GET", path, query, SignatureService.emptyPayloadHash());
        String signature = TestSigner.signature(TestSigner.APP_SECRET, canonical);
        URI uri = URI.create("http://127.0.0.1:" + port + path + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("X-Device-Id", "a-device")
                .header("X-Relay-Chain", chain)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

> 说明：假 B 不验签；这个测试只验证 A 的弹出链头、路径/query 保留、外层签名头和 `X-Relay-Via`。

- [ ] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayForwardIntegrationTest test`

Expected: `/api/relay/api/files` 404（没有 `RelayForwardFilter`）。

- [ ] **Step 3: 加 `HttpClient` Bean**

在 `AppConfig` 中加：

```java
    @Bean("relayHttpClient")
    public java.net.http.HttpClient relayHttpClient(AppProperties properties) {
        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(
                        properties.getRelay().getConnectTimeoutMs()))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }
```

- [ ] **Step 4: 实现过滤器**

创建 `RelayForwardFilter.java`：

```java
package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.audit.RelayViaCodec;
import com.wenshan.upload.audit.RelayViaHop;
import com.wenshan.upload.common.ApiResponse;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.security.CachedBodyHttpServletRequest;
import com.wenshan.upload.security.SignatureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RelayForwardFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/relay";
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            "Content-Type", "Content-Disposition", "Content-Length",
            "Cache-Control", "ETag", "Last-Modified",
            "X-Request-Id", "X-Relay-Request-Id");

    private final AppProperties properties;
    private final RelayChainCodec chainCodec;
    private final RelayViaCodec viaCodec;
    private final RelayUrlGuard urlGuard;
    private final RelayErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RelayForwardFilter(
            AppProperties properties,
            RelayChainCodec chainCodec,
            RelayViaCodec viaCodec,
            RelayUrlGuard urlGuard,
            RelayErrorMapper errorMapper,
            ObjectMapper objectMapper,
            @Qualifier("relayHttpClient") HttpClient httpClient) {
        this.properties = properties;
        this.chainCodec = chainCodec;
        this.viaCodec = viaCodec;
        this.urlGuard = urlGuard;
        this.errorMapper = errorMapper;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.getRelay().isEnabled()) {
            writeError(response, ErrorCode.RELAY_DISABLED);
            return;
        }
        try {
            forward(request, response);
        } catch (BizException e) {
            writeError(response, e.getErrorCode(), e.getMessage());
        } catch (HttpTimeoutException e) {
            writeError(response, ErrorCode.RELAY_TIMEOUT);
        } catch (IOException e) {
            writeError(response, ErrorCode.RELAY_TARGET_UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(response, ErrorCode.RELAY_TARGET_UNREACHABLE);
        }
    }

    private void forward(HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        String path = request.getRequestURI();
        String originalPath = path.substring(PREFIX.length());
        if (!originalPath.startsWith("/api/")) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "转发路径无效");
        }

        List<RelayHop> chain = chainCodec.decode(
                request.getHeader("X-Relay-Chain"),
                properties.getRelay().getMaxHops(),
                properties.getRelay().getMaxChainHeaderBytes());

        RelayHop head = chain.get(0);
        String appKey = (String) request.getAttribute(SignatureFilter.ATTR_APP_KEY);
        String deviceId = (String) request.getAttribute(SignatureFilter.ATTR_DEVICE_ID);
        if (appKey == null || !appKey.equals(head.appKey())) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "链头 AppKey 与请求不一致");
        }
        if (deviceId == null || !deviceId.equals(head.deviceId())) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "链头设备与请求不一致");
        }
        if (chain.size() < 2) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "转发链没有下一跳");
        }

        List<RelayHop> remaining = chain.subList(1, chain.size());
        RelayHop next = remaining.get(0);
        URI base = urlGuard.validate(next.url());
        String targetPath = remaining.size() == 1
                ? originalPath
                : PREFIX + originalPath;
        String targetBase = base.toString().replaceAll("/+$", "");
        URI target = appendQuery(URI.create(targetBase + targetPath), request.getQueryString());

        List<RelayViaHop> incoming = viaCodec.decode(
                request.getHeader("X-Relay-Via"),
                properties.getRelay().getMaxViaHeaderBytes());
        RelayViaHop self = new RelayViaHop(head.appKey(), head.name(), head.url());
        List<RelayViaHop> via = viaCodec.append(
                incoming, self, properties.getRelay().getMaxViaHeaderBytes());

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout(request))
                .method(request.getMethod(), bodyPublisher(request))
                .header("X-App-Key", next.appKey())
                .header("X-Timestamp", String.valueOf(next.timestamp()))
                .header("X-Nonce", next.nonce())
                .header("X-Signature", next.signature())
                .header("X-Device-Id", next.deviceId())
                .header("X-Relay-Via", viaCodec.encode(via, properties.getRelay().getMaxViaHeaderBytes()));

        if (remaining.size() > 1) {
            builder.header("X-Relay-Chain", chainCodec.encode(remaining));
        }
        copyHeader(request, builder, "X-Device-Mac");
        copyHeader(request, builder, "X-Content-Sha256");
        copyHeader(request, builder, "Content-Type");
        copyHeader(request, builder, "Accept");
        copyHeader(request, builder, "User-Agent");
        // targetPath 只用于测试/日志断言；真正的下游路径由 URI 携带。
        if (!targetPath.startsWith("/api/")) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "转发路径无效");
        }

        HttpResponse<InputStream> downstream = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());

        ErrorCode mapped = errorMapper.map(downstream.statusCode(), null);
        if (mapped != null) {
            closeQuietly(downstream.body());
            writeError(response, mapped, "转发目标返回 " + downstream.statusCode());
            return;
        }

        response.setStatus(downstream.statusCode());
        for (String name : RESPONSE_HEADERS) {
            downstream.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
        }
        response.setContentType(downstream.headers()
                .firstValue("Content-Type")
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
        if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
            try (InputStream in = downstream.body(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } else {
            closeQuietly(downstream.body());
        }
    }

    private Duration timeout(HttpServletRequest request) {
        String contentType = request.getContentType();
        boolean upload = request.getRequestURI().contains("/upload")
                || (contentType != null && (contentType.startsWith("multipart/")
                || contentType.equalsIgnoreCase(MediaType.APPLICATION_OCTET_STREAM_VALUE)));
        int millis = upload
                ? properties.getRelay().getUploadTimeoutMs()
                : properties.getRelay().getRequestTimeoutMs();
        return Duration.ofMillis(millis);
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length == 0 && request.getHeader("Transfer-Encoding") == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return HttpRequest.BodyPublishers.ofByteArray(cached.getCachedBody());
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private URI appendQuery(URI base, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return base;
        }
        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(base + separator + rawQuery);
    }

    private void copyHeader(HttpServletRequest request, HttpRequest.Builder builder, String name) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) {
            builder.header(name, value);
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        writeError(response, code, code.getMessage());
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
        response.flushBuffer();
    }

    private void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 忽略关闭失败
        }
    }
}
```

> 注意：`targetPath` 变量只用于断言“路径仍以 /api/ 开头”，真正的下游路径由 `target` URI 决定；下一跳是 relay 时 `URI` 路径已经是 `/api/relay` + 原路径。

- [ ] **Step 5: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayForwardIntegrationTest test`

Expected: 测试通过；假 B 看到 `/api/files?keyword=abc`、`X-App-Key: b-app`、`X-Relay-Via` 解码后含 `A`。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/relay/RelayForwardFilter.java \
        server/src/main/java/com/wenshan/upload/config/AppConfig.java \
        server/src/test/java/com/wenshan/upload/relay/RelayForwardIntegrationTest.java
git commit -m "feat(scope-ftp): forward relay JSON requests"
```

---

## Task 6: 流式 body（multipart / octet-stream / 下载）

**Files:**
- Modify: `server/src/main/java/com/wenshan/upload/relay/RelayForwardFilter.java`（若 Task 5 已按上述实现，通常无需改）
- Test: `server/src/test/java/com/wenshan/upload/relay/RelayForwardStreamingTest.java`

- [ ] **Step 1: 写失败测试（8MB octet 分片 + 下载）**

```java
package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.relay.enabled=true",
                "app.relay.allow-loopback=true",
                "app.security.client-identity-enforced=false"
        })
class RelayForwardStreamingTest {

    @LocalServerPort
    int port;

    private HttpServer downstream;
    private String downstreamUrl;
    private final AtomicInteger receivedBytes = new AtomicInteger();
    private byte[] downloaded;

    @BeforeEach
    void startDownstream() throws Exception {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/api/files/upload/up-1/chunk", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                receivedBytes.set(in.readAllBytes().length);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        downstream.createContext("/api/files/abc/download", exchange -> {
            downloaded = new byte[8 * 1024 * 1024];
            Arrays.fill(downloaded, (byte) 7);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, downloaded.length);
            exchange.getResponseBody().write(downloaded);
            exchange.close();
        });
        downstream.start();
        downstreamUrl = "http://127.0.0.1:" + downstream.getAddress().getPort();
    }

    @AfterEach
    void stopDownstream() {
        downstream.stop(0);
    }

    @Test
    void streamsOctetChunkWithoutBuffering() throws Exception {
        byte[] chunk = new byte[8 * 1024 * 1024];
        Arrays.fill(chunk, (byte) 9);
        String sha = TestSigner.sha256(chunk);
        String path = "/api/relay/api/files/upload/up-1/chunk";
        String query = "index=0";
        String chain = chainHeader(downstreamUrl, "B", "b-app", "b-device");

        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String canonical = TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce,
                "PUT", path, query, sha);
        String signature = TestSigner.signature(TestSigner.APP_SECRET, canonical);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path + "?" + query))
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("X-Device-Id", "a-device")
                .header("X-Content-Sha256", sha)
                .header("Content-Type", "application/octet-stream")
                .header("X-Relay-Chain", chain)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedBytes.get()).isEqualTo(chunk.length);
    }

    @Test
    void streamsDownloadBytes() throws Exception {
        String path = "/api/relay/api/files/abc/download";
        String query = "";
        String chain = chainHeader(downstreamUrl, "B", "b-app", "b-device");
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String canonical = TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce,
                "GET", path, query, SignatureService.emptyPayloadHash());
        String signature = TestSigner.signature(TestSigner.APP_SECRET, canonical);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("X-Device-Id", "a-device")
                .header("X-Relay-Chain", chain)
                .GET()
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(8 * 1024 * 1024);
        assertThat(response.body()[0]).isEqualTo((byte) 7);
    }

    private String chainHeader(String url, String name, String appKey, String deviceId) throws Exception {
        RelayHop a = new RelayHop("http://127.0.0.1:" + port, "A",
                TestSigner.APP_KEY, System.currentTimeMillis(), TestSigner.newNonce(), "ignored", "a-device");
        RelayHop b = new RelayHop(url, name, appKey, System.currentTimeMillis(),
                TestSigner.newNonce(), "ignored", deviceId);
        String json = new ObjectMapper().writeValueAsString(List.of(a, b));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=RelayForwardStreamingTest test`

Expected: 2 个测试通过；若 `receivedBytes` 不是 8MB 或 OOM，检查 `bodyPublisher` 是否走了 `CachedBodyHttpServletRequest` 分支。

- [ ] **Step 3: multipart 回归**

multipart 与 octet 共用 Task 5 的 `bodyPublisher` 流式分支；本 Task 不重复写假 multipart 测试，
在 Task 12 的三机联调中上传一个小文件验证 `multipart/form-data` 经 A 转发到 B。

Run（Task 12 联调时）：在客户端向 B 上传 `a.txt`，确认 B 的 `data/` 出现文件且 A 日志有 `relay.upload`。

Expected: 上传成功，A 不缓存整个 multipart body。

- [ ] **Step 4: 提交**

```bash
git add server/src/test/java/com/wenshan/upload/relay/RelayForwardStreamingTest.java
git commit -m "test(scope-ftp): cover streaming relay bodies"
```

---

## Task 7: 来源审计（relay_via）与 `/api/relay` 路径归一

**Files:**
- Modify: `server/src/main/java/com/wenshan/upload/audit/AuditEvent.java`
- Modify: `server/src/main/java/com/wenshan/upload/audit/AuditLogService.java`
- Modify: `server/src/main/java/com/wenshan/upload/audit/AuditStore.java`
- Modify: `server/src/main/java/com/wenshan/upload/audit/AuditAction.java`
- Modify: `server/src/main/java/com/wenshan/upload/dto/AuditLogItem.java`
- Modify: `server/src/main/java/com/wenshan/upload/security/SignatureFilter.java`
- Test: `server/src/test/java/com/wenshan/upload/audit/AuditActionTest.java`
- Test: `server/src/test/java/com/wenshan/upload/audit/AuditStoreMigrationTest.java`

- [ ] **Step 1: 写 `AuditAction` 失败测试**

```java
package com.wenshan.upload.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionTest {

    @Test
    void resolvesRelayWrappedPathWithRelayPrefix() {
        assertThat(AuditAction.resolve("GET", "/api/relay/api/files"))
                .isEqualTo("relay.files.list");
        assertThat(AuditAction.resolve("POST", "/api/relay/api/files/upload/init"))
                .isEqualTo("relay.upload.init");
        assertThat(AuditAction.resolve("GET", "/api/relay/api/audit/logs"))
                .isEqualTo("relay.audit.list");
    }

    @Test
    void keepsNormalPathAction() {
        assertThat(AuditAction.resolve("GET", "/api/files")).isEqualTo("files.list");
    }
}
```

- [ ] **Step 2: 写迁移失败测试**

```java
package com.wenshan.upload.audit;

import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class AuditStoreMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void addsRelayViaColumnToOldDatabase() throws Exception {
        Path db = tempDir.resolve(AuditStore.DB_FILE_NAME);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts TEXT, request_id TEXT, app_key TEXT, client_name TEXT, device_id TEXT,
                        client_ip TEXT, method TEXT, path TEXT, query TEXT, action TEXT,
                        root TEXT, target_root TEXT, path_param TEXT, target_dir TEXT,
                        session_id TEXT, terminal_command TEXT, body TEXT, content_sha256 TEXT,
                        request_bytes INTEGER, response_bytes INTEGER, duration_ms INTEGER,
                        http_status INTEGER, success INTEGER, error TEXT, user_agent TEXT,
                        file_name TEXT, created_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("INSERT INTO audit_log(ts, app_key, created_at) "
                    + "VALUES ('2026-01-01T00:00:00Z', 'old-app', 1)");
        }
        AppProperties properties = new AppProperties();
        properties.getAudit().setEnabled(true);
        properties.getAudit().setDir(tempDir.toString());

        new AuditStore(properties);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(audit_log)")) {
            boolean found = false;
            while (resultSet.next()) {
                if ("relay_via".equals(resultSet.getString("name"))) {
                    found = true;
                }
            }
            assertThat(found).isTrue();
            assertThat(Files.exists(db)).isTrue();
        }
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=AuditActionTest,AuditStoreMigrationTest test`

Expected: `AuditActionTest` 失败（relay 前缀未剥离）；迁移测试失败（无 `relay_via`）。

- [ ] **Step 4: 改记录与 DTO**

`AuditEvent` 在 `fileName` 后加：

```java
        String fileName,
        java.util.List<RelayViaHop> relayVia
```

`AuditLogItem` 在 `fileName` 后加：

```java
        String fileName,
        java.util.List<RelayViaHop> relayVia
```

- [ ] **Step 5: 改 `AuditAction`**

在 `resolve` 开头归一化路径：

```java
        String uri = path == null ? "" : path;
        if (uri.startsWith("/api/relay/api/")) {
            return "relay." + resolve(method, uri.substring("/api/relay".length()));
        }
```

把后面的 `String uri` 删除，避免重复声明。

- [ ] **Step 6: 改 `AuditStore`**

在类字段区加：

```java
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
```

`COLUMNS` 改成：

```java
    private static final String COLUMNS = "id, ts, request_id, app_key, client_name, device_id, client_ip, "
            + "method, path, query, action, root, target_root, path_param, target_dir, session_id, "
            + "terminal_command, body, content_sha256, request_bytes, response_bytes, duration_ms, "
            + "http_status, success, error, user_agent, file_name, relay_via, created_at";
```

`insertOnce` 的 INSERT 改成：

```java
                    "INSERT INTO audit_log(ts, request_id, app_key, client_name, device_id, client_ip, "
                            + "method, path, query, action, root, target_root, path_param, target_dir, "
                            + "session_id, terminal_command, body, content_sha256, request_bytes, "
                            + "response_bytes, duration_ms, http_status, success, error, user_agent, "
                            + "file_name, relay_via, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
```

绑定处改成：

```java
                setNullableString(statement, index++, event.fileName());
                setNullableString(statement, index++, relayViaJson(event.relayVia()));
                statement.setLong(index, createdAtMillis(event.ts()));
```

加方法：

```java
    private static String relayViaJson(java.util.List<RelayViaHop> relayVia) {
        if (relayVia == null || relayVia.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(relayVia);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.util.List<RelayViaHop> parseRelayVia(String json) {
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        try {
            return MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<RelayViaHop>>() {
                    });
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
```

`mapItem` 末尾加：

```java
                resultSet.getString("file_name"),
                parseRelayVia(resultSet.getString("relay_via")));
```

`initSchema` 的 CREATE TABLE 在 `file_name TEXT,` 后加：

```sql
                        relay_via TEXT,
```

CREATE 之后加：

```java
            ensureColumn(connection, "relay_via",
                    "ALTER TABLE audit_log ADD COLUMN relay_via TEXT");
```

加方法：

```java
    private static void ensureColumn(Connection connection, String column, String ddl)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(audit_log)")) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }
```

- [ ] **Step 7: 改 `AuditLogService`**

构造函数加参数与字段：

```java
    private final com.wenshan.upload.audit.RelayViaCodec relayViaCodec;

    public AuditLogService(AppProperties properties, AuditStore auditStore,
                           com.wenshan.upload.audit.RelayViaCodec relayViaCodec) {
        this.properties = properties;
        this.auditStore = auditStore;
        this.relayViaCodec = relayViaCodec;
        // 其余行保持原构造体不变（从 `AppProperties.Audit config = properties.getAudit();` 开始）。
```

`buildEvent` 在构造 `AuditEvent` 前加：

```java
        java.util.List<RelayViaHop> relayVia;
        try {
            relayVia = relayViaCodec.decode(
                    request.getHeader("X-Relay-Via"),
                    properties.getRelay().getMaxViaHeaderBytes());
        } catch (RuntimeException e) {
            // 来源头损坏不能影响审计落盘；记录为空即可。
            relayVia = java.util.List.of();
        }
```

`new AuditEvent(...)` 最后加 `relayVia` 参数：

```java
                request.getHeader("User-Agent"),
                fileName,
                relayVia);
```

- [ ] **Step 8: 改 `SignatureFilter` 路径归一**

在 `doFilterInternal` 中把：

```java
        String path = request.getRequestURI();
```

改成：

```java
        String path = effectivePath(request.getRequestURI());
```

加方法：

```java
    private static String effectivePath(String path) {
        if (path != null && path.startsWith("/api/relay/api/")) {
            return path.substring("/api/relay".length());
        }
        return path;
    }
```

`AuditLogService` 仍然接收原始 `path`（审计里能看到 `/api/relay/api/...`），但 `AuditAction` 已剥离前缀。

- [ ] **Step 9: 运行测试**

Run: `./scripts/mvn.sh -f server/pom.xml -Dtest=AuditActionTest,AuditStoreMigrationTest test`

Expected: 通过；旧库自动加列，旧行 `relay_via` 为 NULL，查询不报错。

- [ ] **Step 10: 跑服务端全量测试**

Run: `./scripts/mvn.sh -f server/pom.xml test`

Expected: `BUILD SUCCESS`。如果 `AuditLogService` 构造器变化导致测试编译失败，给测试里的构造调用补 `new RelayViaCodec(new ObjectMapper())`。

- [ ] **Step 11: 提交**

```bash
git add server/src/main/java/com/wenshan/upload/audit/AuditEvent.java \
        server/src/main/java/com/wenshan/upload/audit/AuditLogService.java \
        server/src/main/java/com/wenshan/upload/audit/AuditStore.java \
        server/src/main/java/com/wenshan/upload/audit/AuditAction.java \
        server/src/main/java/com/wenshan/upload/dto/AuditLogItem.java \
        server/src/main/java/com/wenshan/upload/security/SignatureFilter.java \
        server/src/test/java/com/wenshan/upload/audit/AuditActionTest.java \
        server/src/test/java/com/wenshan/upload/audit/AuditStoreMigrationTest.java
git commit -m "feat(scope-ftp): record relay provenance in audit"
```

---

## Task 8: 客户端配置 v3（树、迁移、级联删除、空配置）

**Files:**
- Modify: `client/src-tauri/src/config.rs`
- Test: `client/src-tauri/src/config.rs`（文件底部 `#[cfg(test)]`）

- [ ] **Step 1: 写失败测试**

在 `config.rs` 测试模块中加：

```rust
    #[test]
    fn v2_config_migrates_to_v3_direct_servers() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(&path, r#"{"activeServerId":"a","servers":[{"id":"a","name":"A","serverUrl":"http://a","deviceId":"da","deviceName":"dev","appKey":"ka","appSecret":"sa"}]}"#);
        let config = load_or_init(&path).unwrap();
        assert_eq!(config.config_version, 3);
        assert_eq!(config.servers[0].kind, ServerKind::Direct);
        assert!(config.servers[0].parent_id.is_none());
    }

    #[test]
    fn delete_cascades_and_allows_empty_config() {
        let mut config = AppConfig {
            config_version: 3,
            active_server_id: "a".into(),
            servers: vec![],
        };
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        let c = config
            .save_relay(None, &b.id, "C", "http://c", "dev", "kc", Some("sc"))
            .unwrap();
        let deleted = config.delete_server(&a.id).unwrap();
        assert_eq!(deleted.len(), 3);
        assert!(config.servers.is_empty());
        assert!(config.active_server_id.is_empty());
        assert!(config.view().active_server.is_none());
        assert!(config.resolve_chain(&c.id).is_err());
    }

    #[test]
    fn empty_config_is_not_repopulated_on_reload() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(&path, r#"{"configVersion":3,"activeServerId":"","servers":[]}"#);
        let config = load_or_init(&path).unwrap();
        assert!(config.servers.is_empty());
        assert!(config.active_server_id.is_empty());
        let reloaded = load_or_init(&path).unwrap();
        assert!(reloaded.servers.is_empty());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd client/src-tauri && cargo test config::tests::v2_config_migrates_to_v3_direct_servers`

Expected: 编译失败，`ServerKind` / `save_relay` 不存在。

- [ ] **Step 3: 加数据模型**

在 `ServerProfile` 前加：

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServerKind {
    Direct,
    Relay,
}

impl Default for ServerKind {
    fn default() -> Self {
        Self::Direct
    }
}
```

`ServerProfile` 加字段：

```rust
    #[serde(default)]
    pub kind: ServerKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
```

`ServerProfile::new` 返回值加：

```rust
            kind: ServerKind::Direct,
            parent_id: None,
```

新增：

```rust
    pub fn new_relay(
        name: &str,
        server_url: &str,
        device_name: &str,
        app_key: &str,
        app_secret: &str,
        parent_id: &str,
    ) -> Self {
        let mut profile = Self::new(name, server_url, device_name);
        profile.kind = ServerKind::Relay;
        profile.parent_id = Some(parent_id.to_string());
        profile.app_key = app_key.to_string();
        profile.app_secret = app_secret.to_string();
        profile
    }
```

`effective_app_key` 改成：

```rust
    pub fn effective_app_key(&self) -> &str {
        if self.kind == ServerKind::Relay {
            return self.app_key.trim();
        }
        let custom = self.app_key.trim();
        if custom.is_empty() {
            builtin::app_key()
        } else {
            custom
        }
    }
```

`effective_app_secret` 同理：relay 直接返回 `self.app_secret.trim()`，不回退内置值。

`is_configured` 改成：

```rust
    pub fn is_configured(&self) -> bool {
        if self.server_url.trim().is_empty() {
            return false;
        }
        if self.kind == ServerKind::Relay {
            return !self.app_key.trim().is_empty() && !self.app_secret.trim().is_empty();
        }
        true
    }
```

`ServerView` 加：

```rust
    pub kind: ServerKind,
    pub parent_id: Option<String>,
    pub parent_name: Option<String>,
    pub chain_path: Vec<String>,
    pub chain_hops: Vec<ChainHop>,
    pub root_id: String,
    pub root_name: String,
```

新增：

```rust
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChainHop {
    pub id: String,
    pub name: String,
}
```

`ServerProfile::view` 返回：

```rust
            kind: self.kind,
            parent_id: self.parent_id.clone(),
            parent_name: None,
            chain_path: Vec::new(),
            chain_hops: Vec::new(),
            root_id: self.id.clone(),
            root_name: self.name.clone(),
```

`ConfigView` 加：

```rust
    pub config_version: u32,
    pub active_server: Option<ServerView>,
```

`AppConfig` 加：

```rust
    #[serde(default)]
    pub config_version: u32,
```

> 注意：`ServerProfile` / `AppConfig` 新增字段后，`config.rs` 测试里所有结构体字面量都要补
> `kind: ServerKind::Direct, parent_id: None`（或 `config_version: 3`）。优先用
> `..AppConfig::default()` / `..ServerProfile::new(...)` 减少重复；编译失败时逐个补。

- [ ] **Step 4: 实现链解析、视图与保存**

在 `AppConfig` 中加：

```rust
    pub fn resolve_chain(&self, id: &str) -> Result<Vec<&ServerProfile>, String> {
        let mut chain = Vec::new();
        let mut seen = std::collections::HashSet::new();
        let mut current = self
            .find(id)
            .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?;
        loop {
            if !seen.insert(current.id.clone()) {
                return Err("转发链存在环".to_string());
            }
            chain.push(current);
            let Some(parent_id) = current.parent_id.as_deref() else {
                break;
            };
            current = self
                .find(parent_id)
                .ok_or_else(|| "上级服务器不存在，请先修复配置".to_string())?;
            if chain.len() > 64 {
                return Err("转发层级过深".to_string());
            }
        }
        chain.reverse();
        Ok(chain)
    }

    pub fn save_relay(
        &mut self,
        id: Option<&str>,
        parent_id: &str,
        name: &str,
        server_url: &str,
        device_name: &str,
        app_key: &str,
        app_secret: Option<&str>,
    ) -> Result<ServerProfile, String> {
        let snapshot = self.clone();
        let parent = self
            .find(parent_id)
            .ok_or_else(|| "上级服务器不存在".to_string())?;
        if parent.id.is_empty() {
            return Err("上级服务器无效".to_string());
        }
        let parent_id = parent.id.clone();
        let name = normalize_server_name(name)?;
        let server_url = normalize_server_url(server_url)?;
        if server_url.is_empty() {
            return Err("服务器地址不能为空".to_string());
        }
        let device_name = normalize_device_name(device_name)?;
        let app_key = normalize_credential(app_key, "AppKey", 128)?;
        if app_key.is_empty() {
            return Err("转发目标 AppKey 必填".to_string());
        }
        let app_secret = match app_secret {
            Some(value) => Some(normalize_credential(value, "AppSecret", 256)?),
            None => None,
        };

        let mut relay = if let Some(id) = id.map(str::trim).filter(|value| !value.is_empty()) {
            let server = self
                .servers
                .iter()
                .find(|server| server.id == id)
                .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?
                .clone();
            if server.kind != ServerKind::Relay {
                return Err("该服务器不是转发目标".to_string());
            }
            server
        } else {
            ServerProfile::new_relay(&name, &server_url, &device_name, &app_key,
                app_secret.as_deref().unwrap_or_default(), &parent_id)
        };
        relay.kind = ServerKind::Relay;
        relay.parent_id = Some(parent_id.clone());
        relay.name = name;
        relay.server_url = server_url;
        relay.device_name = device_name;
        relay.app_key = app_key;
        if let Some(secret) = app_secret {
            if !secret.is_empty() {
                relay.app_secret = secret;
            }
        }
        if relay.kind == ServerKind::Relay && relay.app_secret.trim().is_empty() {
            return Err("转发目标 AppSecret 必填".to_string());
        }
        relay.ensure_ids();

        if let Some(index) = self.servers.iter().position(|server| server.id == relay.id) {
            self.servers[index] = relay.clone();
        } else {
            self.servers.push(relay.clone());
            self.active_server_id = relay.id.clone();
        }
        // 用 resolve_chain 验证环/父存在；失败则回滚到调用前的克隆。
        if let Err(error) = self.resolve_chain(&relay.id) {
            *self = snapshot;
            return Err(error);
        }
        Ok(relay)
    }
```

- [ ] **Step 5: 改删除与 normalize**

`delete_server` 改成：

```rust
    pub fn delete_server(&mut self, id: &str) -> Result<Vec<String>, String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("缺少要删除的服务器 id".to_string());
        }
        if self.find(id).is_none() {
            return Err("服务器不存在，可能已被删除".to_string());
        }
        let mut doomed = std::collections::HashSet::new();
        let mut stack = vec![id.to_string()];
        while let Some(current) = stack.pop() {
            if !doomed.insert(current.clone()) {
                continue;
            }
            for server in &self.servers {
                if server.parent_id.as_deref() == Some(current.as_str()) {
                    stack.push(server.id.clone());
                }
            }
        }
        let fallback = self
            .find(id)
            .and_then(|server| server.parent_id.clone())
            .filter(|parent| !doomed.contains(parent))
            .or_else(|| {
                self.servers
                    .iter()
                    .find(|server| server.kind == ServerKind::Direct
                        && !doomed.contains(&server.id))
                    .map(|server| server.id.clone())
            })
            .unwrap_or_default();
        self.servers.retain(|server| !doomed.contains(&server.id));
        if doomed.contains(&self.active_server_id) {
            self.active_server_id = fallback;
        }
        Ok(doomed.into_iter().collect())
    }
```

`normalize`：

- 删除“`if self.servers.is_empty() { push default }`”整段。
- 在循环里补 `kind` 默认和 `parent_id` 清洗（parent 不能指向自己）。
- `active_valid` 分支改成：

```rust
        if self.servers.is_empty() {
            self.active_server_id.clear();
            changed = true;
        } else if !self.servers.iter().any(|server| server.id == self.active_server_id) {
            self.active_server_id = self
                .servers
                .iter()
                .find(|server| server.kind == ServerKind::Direct)
                .map(|server| server.id.clone())
                .unwrap_or_else(|| self.servers[0].id.clone());
            changed = true;
        }
        if self.config_version < 3 {
            self.config_version = 3;
            changed = true;
        }
```

`default_config` 改成显式创建默认 direct：

```rust
pub fn default_config() -> AppConfig {
    let mut config = AppConfig {
        config_version: 3,
        ..AppConfig::default()
    };
    let server = ServerProfile::new(
        DEFAULT_SERVER_NAME,
        builtin::default_server_url(),
        DEFAULT_DEVICE_NAME,
    );
    config.active_server_id = server.id.clone();
    config.servers.push(server);
    config
}
```

`load_or_init`：

- 文件不存在或内容为空时，`config = default_config(); needs_persist = true;`
- 解析 v2 后，如果 `config.config_version == 0`，设 3，所有 server `kind=Direct`、`parent_id=None`，`needs_persist = true`。
- 仍然调用 `normalize`，但空 `servers` 不再补默认。
- 配置文件存在且 `servers=[]` 时保持空。

`AppConfig::view`：

```rust
    pub fn view(&self) -> ConfigView {
        let mut views = Vec::new();
        for server in &self.servers {
            let mut view = server.view();
            if let Ok(chain) = self.resolve_chain(&server.id) {
                view.parent_name = server
                    .parent_id
                    .as_deref()
                    .and_then(|parent| self.find(parent))
                    .map(|parent| parent.name.clone());
                view.chain_path = chain.iter().map(|hop| hop.name.clone()).collect();
                view.chain_hops = chain
                    .iter()
                    .map(|hop| ChainHop { id: hop.id.clone(), name: hop.name.clone() })
                    .collect();
                view.root_id = chain.first().map(|hop| hop.id.clone()).unwrap_or_default();
                view.root_name = chain.first().map(|hop| hop.name.clone()).unwrap_or_default();
            }
            views.push(view);
        }
        let active = views
            .iter()
            .find(|view| view.id == self.active_server_id)
            .cloned();
        ConfigView {
            config_version: self.config_version,
            active_server: active,
            active_server_id: self
                .active()
                .map(|server| server.id.clone())
                .unwrap_or_default(),
            servers: views,
            app_key: self
                .active()
                .map(|server| server.effective_app_key().to_string())
                .unwrap_or_default(),
            has_secret: self
                .active()
                .map(|server| !server.effective_app_secret().trim().is_empty())
                .unwrap_or(false),
        }
    }
```

> `ServerView` 需要 `Clone`；把 `#[derive(Debug, Clone, Serialize)]` 加上。

- [ ] **Step 6: 运行 config 测试**

Run: `cd client/src-tauri && cargo test config::tests`

Expected: 原有测试 + 新测试全部通过；旧 `delete_server_keeps_at_least_one_and_switches_active` 改成“删到空”的断言。

- [ ] **Step 7: 提交**

```bash
git add client/src-tauri/src/config.rs
git commit -m "feat(scope-ftp): client config v3 relay tree"
```

---

## Task 9: 客户端 `Api` 链签名与 `/api/relay` 路由

**Files:**
- Modify: `client/src-tauri/src/api.rs`
- Test: `client/src-tauri/src/api.rs`（模块内 `#[cfg(test)]`）

- [ ] **Step 1: 写失败测试**

在 `api.rs` 测试模块中加：

```rust
    #[test]
    fn base64url_is_unpadded() {
        assert_eq!(base64_url_no_pad(b"hello"), "aGVsbG8");
    }

    #[test]
    fn relay_wire_path_is_prefixed() {
        let api = test_relay_api();
        assert_eq!(api.wire_path("/api/files"), "/api/relay/api/files");
        assert_eq!(api.base_url().unwrap(), "http://a.example.com");
    }

    #[test]
    fn relay_chain_header_contains_one_entry_per_hop_with_correct_paths() {
        let api = test_relay_api();
        let encoded = api
            .chain_header("GET", "/api/files", "page=1", signer::EMPTY_SHA256, 123, "root-nonce")
            .unwrap()
            .unwrap();
        let json = String::from_utf8(base64_url_decode(&encoded).unwrap()).unwrap();
        let entries: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0]["url"], "http://a.example.com");
        assert_eq!(entries[0]["appKey"], "ka");
        assert_eq!(entries[1]["url"], "http://b.example.com");
        assert_eq!(entries[1]["appKey"], "kb");
        assert_eq!(entries[0]["deviceId"], "da");
        assert_eq!(entries[1]["deviceId"], "db");
    }

    fn test_relay_api() -> Api {
        let root = ServerProfile {
            id: "a".into(),
            name: "A".into(),
            server_url: "http://a.example.com".into(),
            device_id: "da".into(),
            device_name: "dev".into(),
            app_key: "ka".into(),
            app_secret: "sa".into(),
            kind: ServerKind::Direct,
            parent_id: None,
        };
        let relay = ServerProfile {
            id: "b".into(),
            name: "B".into(),
            server_url: "http://b.example.com".into(),
            device_id: "db".into(),
            device_name: "dev".into(),
            app_key: "kb".into(),
            app_secret: "sb".into(),
            kind: ServerKind::Relay,
            parent_id: Some("a".into()),
        };
        Api::from_chain(reqwest::Client::new(), vec![root, relay])
    }

    fn base64_url_decode(input: &str) -> Result<Vec<u8>, String> {
        let mut output = Vec::new();
        let mut buffer: u32 = 0;
        let mut bits = 0;
        for byte in input.bytes() {
            let value = match byte {
                b'A'..=b'Z' => byte - b'A',
                b'a'..=b'z' => byte - b'a' + 26,
                b'0'..=b'9' => byte - b'0' + 52,
                b'-' => 62,
                b'_' => 63,
                _ => return Err("invalid base64url".into()),
            } as u32;
            buffer = (buffer << 6) | value;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                output.push((buffer >> bits) as u8);
                buffer &= (1 << bits) - 1;
            }
        }
        Ok(output)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd client/src-tauri && cargo test api::tests::relay_wire_path_is_prefixed`

Expected: 编译失败，`Api::from_chain` / `wire_path` 不存在。

- [ ] **Step 3: 改 `Api` 结构**

`Api` 改成：

```rust
#[derive(Debug, Clone)]
pub struct Api {
    http: reqwest::Client,
    /// 从根到目标：[A]、[A,B]、[A,B,C]；直连时只有一台。
    chain: Vec<ServerProfile>,
}

impl Api {
    pub fn new(http: reqwest::Client, server: ServerProfile) -> Self {
        Self { http, chain: vec![server] }
    }

    pub fn from_chain(http: reqwest::Client, chain: Vec<ServerProfile>) -> Self {
        assert!(!chain.is_empty(), "relay chain must not be empty");
        Self { http, chain }
    }

    /// 根服务器（A）。
    fn root(&self) -> &ServerProfile {
        &self.chain[0]
    }

    /// 最终目标（B/C）。
    fn target(&self) -> &ServerProfile {
        self.chain.last().expect("chain is not empty")
    }

    fn is_relay(&self) -> bool {
        self.chain.len() > 1
    }

    fn wire_path(&self, path: &str) -> String {
        if self.is_relay() {
            format!("/api/relay{}", path)
        } else {
            path.to_string()
        }
    }

    /// 保留给现有调用方：当前 target 的配置。
    pub fn server(&self) -> &ServerProfile {
        self.target()
    }
```

`base_url` 改为 `self.root().server_url`；`device_id` 改为 `self.root().device_id`。

- [ ] **Step 4: 编写 base64url 与链头结构**

在 `api.rs` 加：

```rust
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayChainEntry {
    url: String,
    name: String,
    app_key: String,
    timestamp: u64,
    nonce: String,
    signature: String,
    device_id: String,
}

fn base64_url_no_pad(input: &[u8]) -> String {
    const TABLE: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut output = String::with_capacity((input.len() * 4 + 2) / 3);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        output.push(TABLE[(b0 >> 2) as usize] as char);
        output.push(TABLE[(((b0 & 0b11) << 4) | (b1 >> 4)) as usize] as char);
        if chunk.len() > 1 {
            output.push(TABLE[(((b1 & 0b1111) << 2) | (b2 >> 6)) as usize] as char);
        }
        if chunk.len() > 2 {
            output.push(TABLE[(b2 & 0b111111) as usize] as char);
        }
    }
    output
}
```

- [ ] **Step 5: 改 `auth_headers` 与 `build_url`**

`auth_headers` 签名不变，内部改成：

```rust
        let mac = require_device_mac()?;
        let root = self.root();
        let timestamp = now_millis();
        let nonce = Uuid::new_v4().to_string();
        let wire_path = self.wire_path(path);
        let signature = signer::sign_request(
            root.effective_app_secret(),
            root.effective_app_key(),
            timestamp,
            &nonce,
            method,
            &wire_path,
            canonical_query,
            payload_hash,
        );

        let mut headers = HeaderMap::new();
        let mut insert = |name: &'static str, value: &str| -> Result<(), String> {
            let value = HeaderValue::from_str(value)
                .map_err(|e| format!("构造请求头 {name} 失败：{e}"))?;
            headers.insert(name, value);
            Ok(())
        };
        insert(HEADER_APP_KEY, root.effective_app_key())?;
        insert(HEADER_TIMESTAMP, &timestamp.to_string())?;
        insert(HEADER_NONCE, &nonce)?;
        insert(HEADER_SIGNATURE, &signature)?;
        if !self.device_id().is_empty() {
            insert(HEADER_DEVICE_ID, self.device_id())?;
        }
        insert(HEADER_DEVICE_MAC, mac)?;

        if let Some(chain) = self.chain_header(
            method,
            path,
            canonical_query,
            payload_hash,
            timestamp,
            &nonce,
        )? {
            insert("X-Relay-Chain", &chain)?;
        }
        for (name, value) in extra {
            insert(name, value)?;
        }
        Ok(headers)
```

新增 `chain_header`（放在 `auth_headers` 之后；测试直接调用它，避免依赖本机 MAC）：

```rust
    fn chain_header(
        &self,
        method: &str,
        path: &str,
        canonical_query: &str,
        payload_hash: &str,
        root_timestamp: u64,
        root_nonce: &str,
    ) -> Result<Option<String>, String> {
        if !self.is_relay() {
            return Ok(None);
        }
        let mut entries = Vec::with_capacity(self.chain.len());
        for (index, hop) in self.chain.iter().enumerate() {
            let hop_timestamp = if index == 0 { root_timestamp } else { now_millis() };
            let hop_nonce = if index == 0 {
                root_nonce.to_string()
            } else {
                Uuid::new_v4().to_string()
            };
            let hop_path = if index + 1 < self.chain.len() {
                format!("/api/relay{}", path)
            } else {
                path.to_string()
            };
            let hop_signature = signer::sign_request(
                hop.effective_app_secret(),
                hop.effective_app_key(),
                hop_timestamp,
                &hop_nonce,
                method,
                &hop_path,
                canonical_query,
                payload_hash,
            );
            entries.push(RelayChainEntry {
                url: hop.server_url.trim_end_matches('/').to_string(),
                name: hop.name.clone(),
                app_key: hop.effective_app_key().to_string(),
                timestamp: hop_timestamp,
                nonce: hop_nonce,
                signature: hop_signature,
                device_id: hop.device_id.clone(),
            });
        }
        let json = serde_json::to_vec(&entries)
            .map_err(|e| format!("转发链序列化失败：{e}"))?;
        Ok(Some(base64_url_no_pad(&json)))
    }
```

`build_url` 改成：

```rust
    fn build_url(&self, path: &str, canonical_query: &str) -> Result<String, String> {
        let mut url = format!("{}{}", self.base_url()?, self.wire_path(path));
        if !canonical_query.is_empty() {
            url.push('?');
            url.push_str(canonical_query);
        }
        Ok(url)
    }
```

- [ ] **Step 6: 改所有显式 URL 与健康检查**

- `health` 保持 `format!("{}/api/health", self.base_url()?)`（health 只到根 A）。
- `verify_connection` 仍先 `health` 再 `send_json GET /api/auth/verify`；relay 时由链签名到目标。
- 搜索 `format!("{}/api`：

Run: `cd client/src-tauri && grep -n 'format!("{}/api' src/api.rs`

把上传、下载、分片等直接拼 URL 的位置改成：

```rust
let url = self.build_url("/api/files/upload", "")?;
```

或带 query 时：

```rust
let url = self.build_url("/api/files/upload/{id}/chunk", canonical_query)?;
```

注意：`build_url` 会自动加 `/api/relay` 前缀，并把 canonical query 原样拼上；不要再手动拼一次 query。

- [ ] **Step 7: 改错误码与重试策略**

`api_error` 加：

```rust
        40010 => "转发链无效",
        40110 => "转发目标鉴权失败",
        40320 => "转发功能未开启",
        50210 => "转发目标不可达",
        50211 => "转发目标返回异常",
        50212 => "转发目标拒绝来源身份",
        50410 => "转发目标超时",
```

`send_json` 的重试条件加：

```rust
                    let non_idempotent = !matches!(method, Method::GET | Method::HEAD);
                    if self.is_relay() && non_idempotent {
                        return Err(err);
                    }
```

这样 relay 的写请求不会被自动重试；读请求和直连保持原行为。

- [ ] **Step 8: 运行 Rust 测试**

Run: `cd client/src-tauri && cargo test api::tests`

Expected: 3 个新测试通过；如果 `signer::EMPTY_SHA256` 不是 public，改用 `signer::empty_payload_hash()` 或测试里调用 `signer::sign_request` 时传 `""` 前先确认现有常量名。

- [ ] **Step 9: 提交**

```bash
git add client/src-tauri/src/api.rs
git commit -m "feat(scope-ftp): sign and route relay chains"
```

---

## Task 10: Tauri 命令（保存/预检/级联删除）

**Files:**
- Modify: `client/src-tauri/src/commands.rs`
- Modify: `client/src-tauri/src/lib.rs`
- Test: `client/src-tauri/src/config.rs` + `cargo test`（命令层以编译 + 配置单测覆盖）

- [ ] **Step 1: 改 `api_from` 为链**

```rust
fn api_from(state: &AppState) -> Result<Api, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let active = config.require_active()?;
    let chain = config
        .resolve_chain(&active.id)?
        .into_iter()
        .cloned()
        .collect::<Vec<ServerProfile>>();
    Ok(Api::from_chain(state.http.clone(), chain))
}
```

- [ ] **Step 2: 改 `delete_server` 清理整棵子树**

```rust
#[tauri::command]
pub async fn delete_server(state: State<'_, AppState>, id: String) -> Result<ConfigView, String> {
    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    let deleted = config.delete_server(&id)?;
    config::persist(&state.config_path, &config)?;

    if let Ok(mut store) = uploads::UploadStore::load(&state.upload_state_path) {
        let mut changed = false;
        for server_id in &deleted {
            changed |= store.remove_by_server_id(server_id) > 0;
        }
        if changed {
            let _ = store.save();
        }
    }

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}
```

- [ ] **Step 3: 新增 `save_relay` 命令**

```rust
#[tauri::command]
pub async fn save_relay(
    state: State<'_, AppState>,
    id: Option<String>,
    parent_id: String,
    name: String,
    server_url: String,
    device_name: String,
    app_key: String,
    app_secret: Option<String>,
) -> Result<ConfigView, String> {
    config::validate_required_credentials(
        id.as_deref(),
        Some(app_key.as_str()),
        app_secret.as_deref(),
    )?;

    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    config.save_relay(
        id.as_deref(),
        &parent_id,
        &name,
        &server_url,
        &device_name,
        &app_key,
        app_secret.as_deref(),
    )?;
    config::persist(&state.config_path, &config)?;

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}
```

- [ ] **Step 4: 新增 `verify_relay_preview` 命令**

```rust
#[tauri::command]
pub async fn verify_relay_preview(
    state: State<'_, AppState>,
    parent_id: String,
    existing_id: Option<String>,
    name: String,
    server_url: String,
    device_name: String,
    app_key: String,
    app_secret: Option<String>,
) -> Result<serde_json::Value, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let parent = config
        .find(&parent_id)
        .ok_or_else(|| "上级服务器不存在".to_string())?;
    let mut chain = config
        .resolve_chain(&parent.id)?
        .into_iter()
        .cloned()
        .collect::<Vec<ServerProfile>>();

    let mut relay = if let Some(existing_id) = existing_id.as_deref() {
        config
            .find(existing_id)
            .cloned()
            .ok_or_else(|| "转发目标不存在".to_string())?
    } else {
        ServerProfile::new_relay(
            &name,
            &server_url,
            &device_name,
            &app_key,
            app_secret.as_deref().unwrap_or_default(),
            &parent.id,
        )
    };
    let server_url = config::normalize_server_url(&server_url)?;
    if server_url.is_empty() {
        return Err("服务器地址不能为空".to_string());
    }
    relay.name = config::normalize_server_name(&name)?;
    relay.server_url = server_url;
    relay.device_name = config::normalize_device_name(&device_name)?;
    relay.app_key = app_key.trim().to_string();
    if let Some(secret) = app_secret.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        relay.app_secret = secret.to_string();
    }
    if relay.app_key.is_empty() {
        return Err("转发目标 AppKey 必填".to_string());
    }
    if relay.app_secret.trim().is_empty() {
        return Err("转发目标 AppSecret 必填".to_string());
    }
    chain.push(relay);

    Api::from_chain(state.http.clone(), chain)
        .verify_connection()
        .await
}
```

- [ ] **Step 5: 注册命令**

`lib.rs` 的 `generate_handler!` 中加：

```rust
            commands::save_relay,
            commands::verify_relay_preview,
```

- [ ] **Step 6: 编译并跑 Rust 全量测试**

Run: `cd client/src-tauri && cargo test`

Expected: `test result: ok`；`commands.rs` 的 `Api` / `ServerProfile` 导入编译通过。

- [ ] **Step 7: 提交**

```bash
git add client/src-tauri/src/commands.rs client/src-tauri/src/lib.rs
git commit -m "feat(scope-ftp): add relay commands and cascade delete"
```

---

## Task 11: 前端类型、树、表单、空态、来源点击

**Files:**
- Modify: `client/src/types.ts`
- Modify: `client/src/api.ts`
- Modify: `client/src/App.tsx`
- Modify: `client/src/components/ServerSidebar.tsx`
- Modify: `client/src/pages/SettingsPage.tsx`
- Modify: `client/src/pages/ServerBrowser.tsx`
- Modify: `client/src/styles.css`
- Test: `cd client && pnpm build` + 手工验收

- [ ] **Step 1: 改 types**

`types.ts`：

```ts
export type ServerKind = "direct" | "relay";

export interface ChainHop {
  id: string;
  name: string;
}

export interface RelayViaHop {
  appKey: string;
  name: string;
  url: string;
}

export interface ServerView {
  // 现有字段
  kind: ServerKind;
  parentId: string | null;
  parentName: string | null;
  chainPath: string[];
  chainHops: ChainHop[];
  rootId: string;
  rootName: string;
}

export interface ConfigView {
  configVersion: number;
  activeServer: ServerView | null;
  activeServerId: string;
  servers: ServerView[];
  appKey: string;
  hasSecret: boolean;
}
```

`AuditLogItem` 末尾加：

```ts
  relayVia?: RelayViaHop[];
```

- [ ] **Step 2: 加 API 包装**

`api.ts`：

```ts
export async function saveRelay(args: {
  id: string | null;
  parentId: string;
  name: string;
  serverUrl: string;
  deviceName: string;
  appKey: string;
  appSecret: string | null;
}): Promise<ConfigView> {
  return invoke<ConfigView>("save_relay", args);
}

export async function verifyRelayPreview(args: {
  parentId: string;
  existingId: string | null;
  name: string;
  serverUrl: string;
  deviceName: string;
  appKey: string;
  appSecret: string | null;
}): Promise<VerifyResult> {
  return invoke<VerifyResult>("verify_relay_preview", args);
}
```

- [ ] **Step 3: `App.tsx` 空态与 active**

`App.tsx` 顶部 antd 导入补 `Button, Empty`：

```ts
import { Alert, App as AntdApp, Button, Empty, Layout, Spin, Typography } from "antd";
```

- `activeServer` 改成：

```ts
  const activeServer: ServerView | null =
    config?.activeServer ??
    config?.servers.find((server) => server.id === config.activeServerId) ??
    null;
```

- `refreshConnection` 在没有 active 时：

```ts
    if (!server) {
      setVerify(null);
      setRoots([]);
      setActiveRootId(null);
      setConnection({ status: "unconfigured", message: "还没有服务器，请先添加" });
      return;
    }
```

- `refreshRoots` 内部加：

```ts
    if (!serverId) {
      setRoots([]);
      setActiveRootId(null);
      return;
    }
```

- 主内容渲染：当 `config && !config.activeServer && config.servers.length === 0` 时，右侧显示：

```tsx
<Empty
  description="还没有服务器"
  image={Empty.PRESENTED_IMAGE_SIMPLE}
>
  <Button type="primary" onClick={handleAddServer}>添加服务器</Button>
</Empty>
```

- 传入 `ServerBrowser` 的 `key` 保持 `server:root`；没有 active 时不渲染 `ServerBrowser`。

- `handleSelectServer`/`handleSaved` 使用后端返回的 `activeServer`，不要自己用 `servers[0]` 兜底。

- `SettingsTarget` 加 `newRelay`，并在 `App.tsx` 计算父节点：

```ts
type SettingsTarget =
  | { kind: "new" }
  | { kind: "edit"; id: string }
  | { kind: "newRelay"; parentId: string }
  | null;

const relayParent =
  settingsTarget?.kind === "newRelay"
    ? (config?.servers.find((server) => server.id === settingsTarget.parentId) ?? null)
    : null;

const handleAddRelay = useCallback(
  (parentId: string) => {
    withLeaveGuard(() => {
      setSettingsTarget({ kind: "newRelay", parentId });
      setView("settings");
    });
  },
  [withLeaveGuard],
);
```

把 `relayParent` / `handleAddRelay` 传给 `SettingsPage` 和 `ServerSidebar`；`SettingsPage` 的 `server` 在
`newRelay` 时仍为 `null`，`relayParent` 用于显示父名和调用 relay 命令。

- [ ] **Step 4: `ServerSidebar.tsx` 递归树**

`ServerSidebar.tsx` 顶部补：

```ts
import type { ReactNode } from "react";
import { Tag } from "antd";
```

并把 `React.ReactNode` 改成 `ReactNode`。

把平铺 `servers.map(...)` 改成递归：

```tsx
  const childrenOf = (parentId: string | null) =>
    servers.filter((server) => (server.parentId ?? null) === parentId);

  const renderServer = (server: ServerView, depth: number): React.ReactNode => {
    const active = server.id === activeServer?.id;
    const children = childrenOf(server.id);
    return (
      <div key={server.id} className="server-tree-node">
        <div
          role="button"
          tabIndex={0}
          className={`server-list-item${active ? " server-list-item-active" : ""}`}
          style={{ paddingLeft: 12 + depth * 16 }}
          data-testid="server-list-item"
          data-server-id={server.id}
          data-server-kind={server.kind}
          data-parent-id={server.parentId ?? ""}
          onClick={() => onSelectServer(server.id)}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              onSelectServer(server.id);
            }
          }}
        >
          <span className={`server-status-dot${active && connection.status === "connected" ? " is-active" : ""}`} />
          <div className="server-list-main">
            <Text strong={active} ellipsis>{server.name}</Text>
            <Text type="secondary" ellipsis>{server.serverUrl}</Text>
          </div>
          {server.kind === "relay" && <Tag data-testid="relay-badge">转发</Tag>}
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            data-testid="edit-relay"
            onClick={(event) => {
              event.stopPropagation();
              onEditServer(server.id);
            }}
          />
          {active && connection.status === "connected" && (
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              aria-label="添加转发目标"
              data-testid="add-relay"
              onClick={(event) => {
                event.stopPropagation();
                onAddRelay(server.id);
              }}
            />
          )}
        </div>
        {children.map((child) => renderServer(child, depth + 1))}
      </div>
    );
  };
```

`ServerSidebarProps` 加：

```ts
  onAddRelay: (parentId: string) => void;
```

渲染：

```tsx
{childrenOf(null).map((server) => renderServer(server, 0))}
{servers.length === 0 && (
  <Text type="secondary" className="server-empty-hint">
    还没有服务器，点击 + 添加。
  </Text>
)}
```

- [ ] **Step 5: `SettingsPage.tsx` 转发表单与 A 的目标列表**

`SettingsPageProps` 加：

```ts
  relayParent: ServerView | null; // 新建 relay 时的父
  onAddRelay: (parentId: string) => void;
  onSelectServer: (id: string) => void;
```

表单 `SettingsFormValues` 沿用 `name/serverUrl/deviceName/appKey/appSecret`。新增：

```ts
  const isRelayNew = isNew && relayParent !== null;
  const isRelay = isRelayNew || (!isNew && server?.kind === "relay");
```

`initialValues` 在 relay 新建时不要预填 A 的地址/凭据：

```ts
    serverUrl: relayParent ? "" : (server?.serverUrl ?? activeServer?.serverUrl ?? FALLBACK_SERVER_URL),
    deviceName: relayParent ? FALLBACK_DEVICE_NAME : (server?.deviceName ?? activeServer?.deviceName ?? FALLBACK_DEVICE_NAME),
    appKey: relayParent ? "" : (server?.appKey ?? ""),
    appSecret: relayParent ? "" : (server?.hasCustomSecret ? SAVED_SECRET_MASK : ""),
```

`children` 用：

```ts
  const children = useMemo(
    () => (server ? (config?.servers ?? []).filter((item) => item.parentId === server.id) : []),
    [config?.servers, server],
  );
```

- relay 表单标签：`serverUrl` 显示「目标中转服务器地址（{relayParent?.name ?? server?.parentName} 可访问）」，
  `appKey` 显示「{name || "目标"} 的 AppKey」，`appSecret` 显示「{name || "目标"} 的 AppSecret」。
- 新建 relay 时按钮文案「验证并添加」，提交前先预检：

```ts
      const verifyData = await verifyRelayPreview({
        parentId: relayParent!.id,
        existingId: null,
        name: values.name,
        serverUrl: values.serverUrl,
        deviceName: values.deviceName,
        appKey: values.appKey,
        appSecret: values.appSecret || null,
      });
      onVerified(verifyData);
      const next = await saveRelay({
        id: null,
        parentId: relayParent!.id,
        name: values.name,
        serverUrl: values.serverUrl,
        deviceName: values.deviceName,
        appKey: values.appKey,
        appSecret: values.appSecret,
      });
      await onSaved(next, { isNew: true });
```

- 编辑 relay 时：

```ts
      const next = await saveRelay({
        id: server!.id,
        parentId: server!.parentId ?? "",
        name: values.name,
        serverUrl: values.serverUrl,
        deviceName: values.deviceName,
        appKey: values.appKey,
        appSecret: values.appSecret === SAVED_SECRET_MASK ? null : values.appSecret,
      });
      await onSaved(next, { isNew: false });
```

- secret 空白/掩码表示保留。
- A（direct）页面在信息卡片中渲染「转发目标（N）」：

```tsx
{server && (
  <Card title={`转发目标（${children.length}）`} data-testid="settings-relays">
    {children.map((child) => (
      <div
        key={child.id}
        role="button"
        className="settings-relay-item"
        data-testid="settings-relay-item"
        onClick={() => onSelectServer(child.id)}
      >
        <Tag>转发</Tag>
        <Text strong>{child.name}</Text>
        <Text type="secondary">{child.serverUrl}</Text>
      </div>
    ))}
    <Button
      disabled={connection.status !== "connected"}
      onClick={() => onAddRelay(server.id)}
    >
      添加转发目标
    </Button>
  </Card>
)}
```

按钮在 `connection.status !== "connected"` 时禁用，落实「A 连接成功前不出现 B 的填写」；B/C 页面同样只在
target 连接成功后显示「添加转发目标」。

- B/C 信息卡显示可点击链：

```tsx
{server && server.chainHops.length > 1 && (
  <Space wrap>
    {server.chainHops.map((hop, index) => (
      <span key={hop.id}>
        {index > 0 && " → "}
        <Button type="link" size="small" onClick={() => onSelectServer(hop.id)}>
          {hop.name}
        </Button>
      </span>
    ))}
  </Space>
)}
```

`children` 用 `config?.servers.filter((item) => item.parentId === server.id)`。

- 删除按钮：去掉 `canDelete` 限制，已保存服务器都显示；新建表单不显示删除。
- 删除确认文案加「如果这是最后一台服务器，删除后进入空配置」。

- [ ] **Step 6: `ServerBrowser.tsx` 标题与来源点击**

`ServerBrowserProps` 加：

```ts
  chainPath?: string[];
  servers?: ServerView[];
  onSelectServer?: (id: string) => void;
```

`ServerBrowser.tsx` 顶部类型导入补 `RelayViaHop` 和 `ServerView`。

标题：

```tsx
<Text strong>{serverName}</Text>
{chainPath && chainPath.length > 1 && (
  <Text type="secondary">（经 {chainPath.slice(0, -1).join(" → ")} 转发）</Text>
)}
```

审计详情中加一个「来源」项：

```tsx
{
  key: "relayVia",
  label: "来源/转发链",
  span: 2,
  children: relayViaNode(auditDetail.relayVia),
}
```

组件内：

```tsx
  const relayViaNode = (via?: RelayViaHop[]) => {
    if (!via || via.length === 0) return "-";
    return via.map((hop, index) => {
      const local = servers?.find(
        (server) => server.effectiveAppKey === hop.appKey || server.serverUrl === hop.url,
      );
      const node = local ? (
        <Button type="link" size="small" onClick={() => onSelectServer?.(local.id)}>
          {hop.name}
        </Button>
      ) : (
        <Text>{hop.name}</Text>
      );
      return (
        <span key={`${hop.appKey}-${index}`}>
          {index > 0 && " → "}
          {node}
        </span>
      );
    });
  };
```

`App.tsx` 给 `ServerBrowser` 传 `chainPath={activeServer?.chainPath}`、`servers={config?.servers}`、`onSelectServer={handleSelectServer}`。

- [ ] **Step 7: 空态与样式**

`styles.css` 加：

```css
.server-tree-node { display: flex; flex-direction: column; }
.settings-relay-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  border-radius: 6px;
}
.settings-relay-item:hover { background: rgba(0, 0, 0, 0.04); }
```

- [ ] **Step 8: 构建**

Run: `cd client && pnpm build`

Expected: `tsc` 0 error，Vite build 成功。

- [ ] **Step 9: 提交**

```bash
git add client/src/types.ts client/src/api.ts client/src/App.tsx \
        client/src/components/ServerSidebar.tsx client/src/pages/SettingsPage.tsx \
        client/src/pages/ServerBrowser.tsx client/src/styles.css
git commit -m "feat(scope-ftp): relay tree UI, relay form, empty config"
```

---

## Task 12: 文档、E2E 与验收

**Files:**
- Modify: `07_scope-ftp/docs/01-architecture.md`
- Modify: `07_scope-ftp/docs/02-api-contract.md`
- Modify: `07_scope-ftp/docs/03-deployment.md`
- Modify: `07_scope-ftp/docs/04-security.md`
- Modify: `07_scope-ftp/README.md`
- Modify: `07_scope-ftp/scripts/e2e.mjs`（如现有脚本覆盖连接/上传，追加 relay 用例）
- Test: 手工联调 + `./scripts/mvn.sh -f server/pom.xml test` + `cd client/src-tauri && cargo test` + `cd client && pnpm build`

- [ ] **Step 1: 更新 API 契约**

`docs/02-api-contract.md` 加一节「转发」：

````markdown
### 转发请求

客户端在访问最终目标时，把原请求发到根服务器的 `/api/relay` + 原 path：

```text
POST /api/relay/api/files/upload/init
X-App-Key: <A 的 appKey>
X-Relay-Chain: <base64url(JSON 数组)>
```

`X-Relay-Chain` 数组每项：`{url,name,appKey,timestamp,nonce,signature,deviceId}`。
每一项的签名：非最后一跳签 `/api/relay` + 原 path，最后一跳签原 path。
中间跳会追加 `X-Relay-Via`，其值为 `{appKey,name,url}[]` 的 base64url JSON。
链里没有 AppSecret。`/api/relay` 默认关闭，错误码 40010/40320/40110/50210/50211/50212/50410。
````

- [ ] **Step 2: 更新架构/部署/安全**

- 架构：客户端 v3 树、`X-Relay-Chain`、`RelayForwardFilter`、`/api/relay` 前缀、流式 body。
- 部署：A/B/C 都要升级并开启 `app.relay.enabled=true`；最终目标数据面可旧版，但来源审计需要升级；旧客户端会忽略 v3，发布窗口限制 B/C 可达性或轮换凭据。
- 安全：无 secret 下行、每跳验签、SSRF 守卫、来源头 P1 依赖网络、P2 可选签名、审计不记录原始链头。

- [ ] **Step 3: README 加操作说明**

写清：

1. 先添加 A 并连接成功；
2. A 设置页「转发目标」→ 添加 B，填 B 的地址/AppKey/AppSecret/设备名；
3. B 下再添加 C；
4. 左侧点击 B/C 切换；
5. 删除 A 会级联删除 B/C；删到最后一台后显示空配置；
6. B/C 审计「来源」可点击切换。

- [ ] **Step 4: 手工 E2E**

准备三台服务端（A/B/C）或至少 A/B：

```bash
# A（端口 9091）
./scripts/mvn.sh -f server/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=9091 --app.relay.enabled=true --app-key=ak-a --app-secret=sk-a-0123456789abcdef0123456789abcdef"
# B（端口 9092；另一个终端）
./scripts/mvn.sh -f server/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=9092 --app-key=ak-b --app-secret=sk-b-0123456789abcdef0123456789abcdef"
```

客户端：

1. 添加 A（地址/key/secret），连接成功；
2. A → 添加 B；客户端验证时 A 健康检查 + B verify；
3. 切换 B，浏览/上传 8MB 分片/下载/删除；
4. B 下添加 C，重复；
5. 查看 B 审计来源 A，C 审计来源 A → B，点击来源切换；
6. 删除 A，确认 B/C 配置和续传状态消失；若 A 是最后一台，显示空态；
7. 重启客户端，确认空配置不被自动补回。

Expected: 全部通过；服务端 `data/` 文件不受删除服务器影响。

- [ ] **Step 5: 跑全量验证**

```bash
./scripts/mvn.sh -f server/pom.xml test
cd client/src-tauri && cargo test
cd ../.. && cd client && pnpm build
```

Expected: 服务端 `BUILD SUCCESS`；Rust `test result: ok`；Vite build 成功。

- [ ] **Step 6: 提交**

```bash
git add docs/01-architecture.md docs/02-api-contract.md docs/03-deployment.md \
        docs/04-security.md README.md scripts/e2e.mjs
git commit -m "docs(scope-ftp): document relay chain"
```

---

## 交付检查清单

- [ ] A 连接成功前，前端没有 B 的地址/凭据输入框。
- [ ] B 表单包含目标地址 + B 的 AppKey/AppSecret + 设备名。
- [ ] 左侧树显示「转发」Tag，点击 B/C 切换；A 信息区显示目标列表且可点击。
- [ ] 删除 A 级联删除 B/C 配置与续传状态；删除最后一台允许，显示空态，重启仍空。
- [ ] B 审计来源 A；C 来源 A → B；来源 hop 可点击切换本地已配置服务器。
- [ ] 编辑已保存 B/C 不要求父链连接；验证连接仍要求父链可用。
- [ ] 链中无 secret；A 改错 key/secret 时 A/B 都失败，修 A 后 B 恢复。
- [ ] relay 默认关闭；关闭时 40320，不影响 direct。
- [ ] 旧审计库升级后可查询，`relayVia` 为 NULL 时 UI 正常。





