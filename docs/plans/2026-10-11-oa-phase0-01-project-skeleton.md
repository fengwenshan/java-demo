# OA 工程骨架 Implementation Plan

> ⚠️ **本计划已被 [R01 部门管理](../requirements/R01-部门管理.md) 取代，不要直接执行。**
>
> 取代的原因：本计划要求一次性把 4 个 Maven 模块、统一返回体、链路追踪、多环境配置、
> Flyway、OpenAPI、协作规范全部建完，再写第一行业务代码。这对"从零开始"的阶段过重——
> 基础设施应当由具体需求倒推着建，而不是提前建好等需求来用。
>
> R01 改用**单模块起步**：先写出一个能跑的部门管理功能，包结构按未来的模块边界划分，
> 等代码量真的撑得起模块拆分时再拆。
>
> 本计划仍有参考价值的部分：技术栈版本选型（第 0 节的环境前置、第 1 节的设计决策）、
> 以及「哪些东西属于基础设施」的清单。**这些内容已在 R01 中按需取用。**
>
> 保留此文件是为了记录决策过程，不再作为执行依据。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 OA 系统的独立 Maven 多模块骨架，具备统一返回体、全局异常处理、参数校验、traceId 链路追踪、多环境与敏感配置外置、Flyway 数据库版本管理、MyBatis 纵向切片、OpenAPI 接口文档，并可通过端到端冒烟验证。

**Architecture:** 单体多模块。`oa/` 是**独立 Maven reactor**（不并入根 `java-demo` 聚合），父 POM 为 `spring-boot-starter-parent:4.0.8`。四个模块严格单向依赖：`oa-boot → oa-system → oa-framework → oa-common`。业务模块统一使用 `com.wenshan.oa` 根包，由 `OaApplication` 的组件扫描覆盖。

**Tech Stack:** Java 21 · Maven 3.9.15 · Spring Boot 4.0.8 · MyBatis Spring Boot Starter 4.1.0 · MySQL 9.7.0（Boot BOM 管理）· Flyway 11.14.1（Boot BOM 管理）· springdoc-openapi 3.1.1 · JUnit 5 + AssertJ + MockMvc

---

## 0. 环境前置（一次性，必须先做）

**这四件事不做，后面每条命令都会失败。** 当前环境有三个已确认的坑：`JAVA_HOME` 指向一个已删除的 JDK 1.8、`mvn` 不在 `PATH`、命令行 Maven 与 IDEA 用的不是同一个本地仓库。

### 0.1 修正 JAVA_HOME 与 PATH

追加到 `~/.zshrc`：

```bash
export JAVA_HOME=/Users/fengwenshan/wenshan/app/java/jdk/jdk-21.0.3.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/Users/fengwenshan/wenshan/app/java/apache-maven-3.9.15/bin:$PATH"
```

生效并验证：

```bash
source ~/.zshrc
java -version
mvn -v
```

预期输出（关键行）：

```
openjdk version "21.0.3" 2024-04-16
Apache Maven 3.9.15 (cdbfbf1acf8781f537ea9acccaed7922349)
Java version: 21.0.3, vendor: Oracle Corporation
```

### 0.2 让命令行 Maven 与 IDEA 共用同一仓库与镜像

IDEA 使用 `/Users/fengwenshan/wenshan/app/java/maven-config/settings.xml`（本地仓库指向 `maven-config/repository`，并配置了阿里云镜像 `mirrorOf=*`）。命令行默认读 `~/.m2/settings.xml`，该文件**不存在**，会导致所有依赖重新下载到另一个目录。

```bash
cp /Users/fengwenshan/wenshan/app/java/maven-config/settings.xml ~/.m2/settings.xml
```

验证：

```bash
mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout
```

预期输出：

```
/Users/fengwenshan/wenshan/app/java/maven-config/repository
```

### 0.3 创建数据库

```bash
docker exec mysql mysql -uroot -p123456 -e \
  "CREATE DATABASE IF NOT EXISTS oa DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
docker exec mysql mysql -uroot -p123456 -e \
  "CREATE DATABASE IF NOT EXISTS oa_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

验证：

```bash
docker exec mysql mysql -uroot -p123456 -e "SHOW DATABASES LIKE 'oa%';"
```

预期输出：

```
Database
oa
oa_test
```

### 0.4 确认 Docker MySQL 处于运行状态

后续 Task 9 起需要真实数据库连接。**每个执行测试的终端会话**在开始前执行：

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep mysql
```

预期输出：`mysql  Up ...`

---

## 1. 本计划锁定的设计决策

这些是团队需要认可的前提。有异议请在评审时提出，不要在执行中改。

| # | 决策 | 理由 |
|---|---|---|
| 1 | `oa/` 独立 reactor，不加入根 `java-demo` 的 `<modules>` | 根 POM 将 `spring-context` 锁定为 6.1.13，而 Boot 4 使用 Spring Framework 7.0.9，继承即冲突 |
| 2 | 父 POM 用 `spring-boot-starter-parent`，而非 import BOM | 骨架需要大量插件管理（compiler、surefire、repackage），starter-parent 开箱即用 |
| 3 | HTTP 状态码表达错误类别（400/401/403/405/500），响应体 `code` 表达业务语义 | 避免"HTTP 200 但业务失败"的监控盲区；网关与告警可按状态码统计 |
| 4 | 业务异常统一 HTTP 400 + 业务码 ≥ 1000 | 与参数校验错误（code 400）区分开 |
| 5 | 返回体用 `record`，实体用普通 POJO | record 适合不可变值对象；实体需无参构造给 MyBatis 用，沿用 `04_spring-boot-demo` 已验证的写法 |
| 6 | 不引入 Lombok | 减少一个编译期依赖与 IDE 插件要求；是否引入留给团队后续决策 |
| 7 | 集成测试连真实 MySQL（Docker），不用 H2 | Flyway 迁移脚本必须针对 MySQL 9.7 验证，H2 兼容模式验证不了 |
| 8 | 各 Mapper 接口加 `@Mapper`，不用 `@MapperScan` 扫包 | `@MapperScan` 会把包内所有接口（含 Service 接口）注册为 Mapper，是踩过的坑 |

**本计划不做的事（明确的范围边界，不是 TODO）：**

- **不创建** `oa-workflow` / `oa-attendance` / `oa-collab` / `oa-doc` / `oa-admin` / `oa-report` 模块——它们各自在对应期次的计划中创建，POM 写法照抄本计划的 `oa-system`
- **不实现** 认证、权限、数据权限拦截器——属于《组织架构与数据权限》计划
- **不处理 415 / 406 等其余标准 MVC 异常**——它们会落入 `Exception` 兜底、返回 500。彻底解决需要把 `@RestControllerAdvice` 改为继承 `ResponseEntityExceptionHandler`，改动面与风险都高于当前收益，留到《接口契约》计划一并处理。**404 已显式处理**，不会退化成 500
- **不引入 Redis**——骨架阶段无使用场景
- **不配置 CI**——仓库到 github.com 的网络当前不通，CI 环境另议

---

## 2. 文件结构

本计划创建的全部文件（`Modify` 表示修改已有文件）：

```
java-demo/
├── .gitignore                                    Modify（追加 oa 相关的忽略项）
└── oa/
    ├── pom.xml                                   Create（聚合 + 父 POM）
    ├── oa-common/
    │   ├── pom.xml                               Create
    │   └── src/
    │       ├── main/java/com/wenshan/oa/common/
    │       │   ├── result/Result.java            Create
    │       │   ├── result/ResultCode.java        Create
    │       │   ├── result/PageQuery.java         Create
    │       │   ├── result/PageResult.java        Create
    │       │   └── exception/BizException.java   Create
    │       └── test/java/com/wenshan/oa/common/result/
    │           ├── ResultTest.java               Create
    │           └── PageResultTest.java           Create
    ├── oa-framework/
    │   ├── pom.xml                               Create
    │   └── src/
    │       ├── main/java/com/wenshan/oa/framework/
    │       │   ├── web/GlobalExceptionHandler.java  Create
    │       │   └── web/TraceIdFilter.java           Create
    │       └── test/java/com/wenshan/oa/framework/web/
    │           ├── GlobalExceptionHandlerTest.java  Create
    │           └── TraceIdFilterTest.java           Create
    ├── oa-system/
    │   ├── pom.xml                               Create
    │   └── src/main/java/com/wenshan/oa/system/
    │       ├── entity/SysConfig.java             Create
    │       ├── mapper/SysConfigMapper.java       Create
    │       ├── service/SysConfigService.java     Create
    │       ├── service/impl/SysConfigServiceImpl.java  Create
    │       ├── controller/SysConfigController.java     Create
    │       └── dto/SysConfigVO.java              Create
    └── oa-boot/
        ├── pom.xml                               Create
        └── src/
            ├── main/java/com/wenshan/oa/OaApplication.java   Create
            ├── main/resources/
            │   ├── application.yml                Create
            │   ├── application-dev.yml            Create
            │   ├── application-test.yml           Create
            │   ├── application-prod.yml           Create
            │   ├── application-local.yml          Create（**已被 .gitignore 忽略，不提交**）
            │   └── db/migration/V1__baseline.sql  Create
            └── test/
                ├── java/com/wenshan/oa/
                │   ├── SecretGuardTest.java                  Create
                │   ├── flyway/FlywayMigrationTest.java       Create
                │   ├── system/SysConfigApiTest.java          Create
                │   └── docs/OpenApiDocsTest.java             Create
                └── resources/（无需额外文件，测试配置走 application-test.yml）
```

---

## Task 1: 独立 reactor 与四模块骨架

**Files:**
- Create: `oa/pom.xml`
- Create: `oa/oa-common/pom.xml`
- Create: `oa/oa-framework/pom.xml`
- Create: `oa/oa-system/pom.xml`
- Create: `oa/oa-boot/pom.xml`
- Modify: `.gitignore`

- [ ] **Step 1: 先建立聚合 POM，并观察它失败**

创建 `oa/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.8</version>
        <relativePath/>
    </parent>

    <groupId>com.wenshan.oa</groupId>
    <artifactId>oa</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>oa</name>
    <description>公司 OA 系统</description>

    <properties>
        <java.version>21</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mybatis-spring-boot.version>4.1.0</mybatis-spring-boot.version>
        <springdoc.version>3.1.1</springdoc.version>
    </properties>

    <modules>
        <module>oa-common</module>
        <module>oa-framework</module>
        <module>oa-system</module>
        <module>oa-boot</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.wenshan.oa</groupId>
                <artifactId>oa-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.wenshan.oa</groupId>
                <artifactId>oa-framework</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.wenshan.oa</groupId>
                <artifactId>oa-system</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.mybatis.spring.boot</groupId>
                <artifactId>mybatis-spring-boot-starter</artifactId>
                <version>${mybatis-spring-boot.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

运行：

```bash
cd oa && mvn validate
```

预期：**FAIL**，报错含

```
Child module /Users/fengwenshan/wenshan/demo/java-demo/oa/oa-common of /Users/fengwenshan/wenshan/demo/java-demo/oa/pom.xml does not exist
```

这一步验证的是：聚合 POM 本身语法正确、Spring Boot 4.0.8 父 POM 能解析成功（若版本号不存在，这里就会报 `Non-resolvable parent POM`，而不是子模块缺失）。**如果报的是父 POM 解析失败，先解决版本问题再继续。**

- [ ] **Step 2: 创建 oa-common 的 POM**

创建 `oa/oa-common/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.wenshan.oa</groupId>
        <artifactId>oa</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>oa-common</artifactId>
    <name>oa-common</name>
    <description>通用返回体、异常体系、值对象</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 创建 oa-framework 的 POM**

创建 `oa/oa-framework/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.wenshan.oa</groupId>
        <artifactId>oa</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>oa-framework</artifactId>
    <name>oa-framework</name>
    <description>全局异常处理、链路追踪、持久层装配（后续含认证与数据权限）</description>

    <dependencies>
        <dependency>
            <groupId>com.wenshan.oa</groupId>
            <artifactId>oa-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: 创建 oa-system 的 POM**

创建 `oa/oa-system/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.wenshan.oa</groupId>
        <artifactId>oa</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>oa-system</artifactId>
    <name>oa-system</name>
    <description>组织架构、员工、项目组、系统参数（后续模块照抄本模块的 POM 结构）</description>

    <dependencies>
        <dependency>
            <groupId>com.wenshan.oa</groupId>
            <artifactId>oa-framework</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: 创建 oa-boot 的 POM**

创建 `oa/oa-boot/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.wenshan.oa</groupId>
        <artifactId>oa</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>oa-boot</artifactId>
    <name>oa-boot</name>
    <description>启动模块：配置、迁移脚本、打包</description>

    <dependencies>
        <dependency>
            <groupId>com.wenshan.oa</groupId>
            <artifactId>oa-system</artifactId>
        </dependency>
        <dependency>
            <groupId>com.wenshan.oa</groupId>
            <artifactId>oa-framework</artifactId>
        </dependency>
        <dependency>
            <groupId>com.wenshan.oa</groupId>
            <artifactId>oa-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>com.wenshan.oa.OaApplication</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 6: 追加 .gitignore 规则**

在仓库根 `.gitignore` 的「构建产物」段之后追加：

```
# ---------- OA ----------
oa/**/src/main/resources/application-local.yml
oa/**/src/main/resources/application-local.yaml
oa/**/src/main/resources/application-local.properties
```

- [ ] **Step 7: 运行校验，确认骨架可解析**

```bash
cd oa && mvn -q validate && echo "REACTOR-OK"
```

预期输出：

```
REACTOR-OK
```

- [ ] **Step 8: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add .gitignore oa
git commit -m "build(oa): 建立独立 Maven reactor 与 common/framework/system/boot 四模块骨架

- 父 POM 使用 spring-boot-starter-parent:4.0.8，与学习模块的聚合彻底隔离
- 模块依赖单向：oa-boot -> oa-system -> oa-framework -> oa-common
- 其余业务模块由各自期次的计划按 oa-system 的 POM 结构添加"
```

---

## Task 2: 统一返回体 Result 与 ResultCode

**Files:**
- Create: `oa/oa-common/src/main/java/com/wenshan/oa/common/result/ResultCode.java`
- Create: `oa/oa-common/src/main/java/com/wenshan/oa/common/result/Result.java`
- Test: `oa/oa-common/src/test/java/com/wenshan/oa/common/result/ResultTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-common/src/test/java/com/wenshan/oa/common/result/ResultTest.java`：

```java
package com.wenshan.oa.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一返回体 Result")
class ResultTest {

    @Test
    @DisplayName("success() 返回 200 与操作成功，data 为 null")
    void successWithoutData() {
        Result<Void> result = Result.success();

        assertThat(result.code()).isEqualTo(200);
        assertThat(result.message()).isEqualTo("操作成功");
        assertThat(result.data()).isNull();
    }

    @Test
    @DisplayName("success(data) 原样携带数据")
    void successWithData() {
        Result<String> result = Result.success("部门A");

        assertThat(result.code()).isEqualTo(200);
        assertThat(result.data()).isEqualTo("部门A");
    }

    @Test
    @DisplayName("failure(ResultCode) 使用枚举自带的码与消息")
    void failureFromCode() {
        Result<Void> result = Result.failure(ResultCode.PARAM_ERROR);

        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).isEqualTo("参数校验失败");
        assertThat(result.data()).isNull();
    }

    @Test
    @DisplayName("failure(code, message) 支持业务自定义码与消息")
    void failureWithCustomCode() {
        Result<Void> result = Result.failure(1001, "部门不存在");

        assertThat(result.code()).isEqualTo(1001);
        assertThat(result.message()).isEqualTo("部门不存在");
    }

    @Test
    @DisplayName("业务码从 1000 起，不与标准错误码冲突")
    void businessCodeRange() {
        assertThat(ResultCode.SYSTEM_ERROR.getCode()).isEqualTo(500);
        assertThat(ResultCode.businessCode(1000)).isGreaterThanOrEqualTo(1000);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-common test -Dtest=ResultTest
```

预期：**编译失败**，报 `找不到符号: 类 Result` / `找不到符号: 类 ResultCode`。

- [ ] **Step 3: 实现 ResultCode**

创建 `oa/oa-common/src/main/java/com/wenshan/oa/common/result/ResultCode.java`：

```java
package com.wenshan.oa.common.result;

/**
 * 标准错误码。
 * <p>
 * 约定：code 与 HTTP 状态码在标准错误上保持一致；业务错误从 {@link #BUSINESS_CODE_BASE} 起编号，
 * 避免与标准码混淆。
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试");

    /** 业务错误码起始值。 */
    public static final int BUSINESS_CODE_BASE = 1000;

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 校验并返回一个业务错误码，防止误用标准错误码。
     *
     * @param code 业务错误码，必须大于等于 {@link #BUSINESS_CODE_BASE}
     * @return 原样返回该码
     * @throws IllegalArgumentException 当码值落在标准错误码区间
     */
    public static int businessCode(int code) {
        if (code < BUSINESS_CODE_BASE) {
            throw new IllegalArgumentException("业务错误码必须大于等于 " + BUSINESS_CODE_BASE + "，实际为 " + code);
        }
        return code;
    }
}
```

- [ ] **Step 4: 实现 Result**

创建 `oa/oa-common/src/main/java/com/wenshan/oa/common/result/Result.java`：

```java
package com.wenshan.oa.common.result;

/**
 * 统一响应体。
 *
 * @param code    业务码，标准错误与 HTTP 状态码对齐，业务错误从 1000 起
 * @param message 提示消息，可直接展示给用户
 * @param data    业务数据，无数据时为 null
 * @param <T>     数据类型
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> failure(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> failure(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-common test -Dtest=ResultTest
```

预期输出：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 6: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-common
git commit -m "feat(oa-common): 新增统一返回体 Result 与标准错误码 ResultCode

业务错误码从 1000 起，并提供 businessCode 校验防止与标准错误码混用"
```

---

## Task 3: 分页对象 PageQuery 与 PageResult

**Files:**
- Create: `oa/oa-common/src/main/java/com/wenshan/oa/common/result/PageQuery.java`
- Create: `oa/oa-common/src/main/java/com/wenshan/oa/common/result/PageResult.java`
- Test: `oa/oa-common/src/test/java/com/wenshan/oa/common/result/PageResultTest.java`

> 本任务只建立**数据模型**。真正的分页查询机制（MyBatis 拦截器改写 SQL）属于《组织架构与数据权限》计划，本计划不实现。

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-common/src/test/java/com/wenshan/oa/common/result/PageResultTest.java`：

```java
package com.wenshan.oa.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("分页对象")
class PageResultTest {

    @Test
    @DisplayName("PageQuery 对非法页码与页大小做兜底")
    void pageQueryNormalizes() {
        PageQuery query = new PageQuery(0, 0, null);

        assertThat(query.pageNum()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(10);
        assertThat(query.sortField()).isNull();
    }

    @Test
    @DisplayName("PageQuery 页大小上限 200，防止全表扫描")
    void pageQueryCapsSize() {
        assertThat(new PageQuery(1, 100000, null).pageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("PageQuery 计算 offset")
    void pageQueryOffset() {
        assertThat(new PageQuery(3, 20, null).offset()).isEqualTo(40);
    }

    @Test
    @DisplayName("PageResult 由 PageQuery 与总数构造，并算出总页数")
    void pageResultComputesTotalPages() {
        PageResult<String> result = PageResult.of(new PageQuery(1, 10, null), 25L, List.of("a", "b"));

        assertThat(result.total()).isEqualTo(25L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.records()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("总数为 0 时总页数为 0，不出现除零")
    void pageResultWithZeroTotal() {
        PageResult<String> result = PageResult.of(new PageQuery(1, 10, null), 0L, List.of());

        assertThat(result.totalPages()).isZero();
        assertThat(result.records()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-common test -Dtest=PageResultTest
```

预期：**编译失败**，报 `找不到符号: 类 PageQuery` / `找不到符号: 类 PageResult`。

- [ ] **Step 3: 实现 PageQuery**

创建 `oa/oa-common/src/main/java/com/wenshan/oa/common/result/PageQuery.java`：

```java
package com.wenshan.oa.common.result;

/**
 * 分页请求参数。
 * <p>
 * 对越界值做静默兜底而非抛异常：分页参数来自前端，非法值不应导致 500。
 *
 * @param pageNum   页码，从 1 开始
 * @param pageSize  每页条数，上限 {@link #MAX_PAGE_SIZE}
 * @param sortField 排序字段，可为 null
 */
public record PageQuery(int pageNum, int pageSize, String sortField) {

    /** 每页条数上限，防止一次拉取过大结果集。 */
    public static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;

    public PageQuery {
        if (pageNum < 1) {
            pageNum = DEFAULT_PAGE_NUM;
        }
        if (pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
    }

    /** 数据库查询偏移量。 */
    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
```

- [ ] **Step 4: 实现 PageResult**

创建 `oa/oa-common/src/main/java/com/wenshan/oa/common/result/PageResult.java`：

```java
package com.wenshan.oa.common.result;

import java.util.List;

/**
 * 分页结果。
 *
 * @param pageNum    当前页码
 * @param pageSize   每页条数
 * @param total      总记录数
 * @param totalPages 总页数
 * @param records    当前页数据
 * @param <T>        记录类型
 */
public record PageResult<T>(int pageNum, int pageSize, long total, int totalPages, List<T> records) {

    public static <T> PageResult<T> of(PageQuery query, long total, List<T> records) {
        int totalPages = query.pageSize() == 0 ? 0 : (int) ((total + query.pageSize() - 1) / query.pageSize());
        return new PageResult<>(query.pageNum(), query.pageSize(), total, totalPages, List.copyOf(records));
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-common test -Dtest=PageResultTest
```

预期输出：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 6: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-common
git commit -m "feat(oa-common): 新增分页对象 PageQuery 与 PageResult

PageQuery 对非法页码与页大小静默兜底，页大小上限 200 防止全表扫描"
```

---

## Task 4: 业务异常 BizException

**Files:**
- Create: `oa/oa-common/src/main/java/com/wenshan/oa/common/exception/BizException.java`
- Test: `oa/oa-common/src/test/java/com/wenshan/oa/common/exception/BizExceptionTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-common/src/test/java/com/wenshan/oa/common/exception/BizExceptionTest.java`：

```java
package com.wenshan.oa.common.exception;

import com.wenshan.oa.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("业务异常 BizException")
class BizExceptionTest {

    @Test
    @DisplayName("用 ResultCode 构造时携带其码与消息")
    void fromResultCode() {
        BizException ex = new BizException(ResultCode.NOT_FOUND);

        assertThat(ex.getCode()).isEqualTo(404);
        assertThat(ex.getMessage()).isEqualTo("资源不存在");
    }

    @Test
    @DisplayName("自定义业务码必须落在业务码区间")
    void customBusinessCode() {
        BizException ex = new BizException(1001, "部门不存在");

        assertThat(ex.getCode()).isEqualTo(1001);
        assertThat(ex.getMessage()).isEqualTo("部门不存在");
    }

    @Test
    @DisplayName("自定义业务码使用标准错误码时立即拒绝")
    void rejectsStandardCode() {
        assertThatThrownBy(() -> new BizException(500, "不该这么用"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务错误码必须大于等于 1000");
    }

    @Test
    @DisplayName("业务异常不采集堆栈，避免高频异常下的性能开销")
    void doesNotFillStackTrace() {
        BizException ex = new BizException(ResultCode.FORBIDDEN);

        assertThat(ex.getStackTrace()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-common test -Dtest=BizExceptionTest
```

预期：**编译失败**，报 `找不到符号: 类 BizException`。

- [ ] **Step 3: 实现 BizException**

创建 `oa/oa-common/src/main/java/com/wenshan/oa/common/exception/BizException.java`：

```java
package com.wenshan.oa.common.exception;

import com.wenshan.oa.common.result.ResultCode;

import java.io.Serial;

/**
 * 业务异常：表示"请求本身合法，但业务规则不允许"。
 * <p>
 * 这类异常由业务代码主动抛出，属于预期内的控制流，因此不采集堆栈——高频业务校验下
 * 采集堆栈会带来明显的性能开销，也污染日志。
 */
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    /**
     * 用标准错误码构造，但自定义提示消息。
     * <p>
     * 本构造函数<b>不做</b>业务码区间校验：{@link ResultCode} 里的码本就是标准错误码，
     * 若复用带校验的构造器，传 {@code FORBIDDEN} 这类码会被误判为非法。
     */
    public BizException(ResultCode resultCode, String message) {
        super(message, null, false, false);
        this.code = resultCode.getCode();
    }

    /**
     * @param code    业务错误码，必须大于等于 {@link ResultCode#BUSINESS_CODE_BASE}
     * @param message 面向用户的提示消息
     */
    public BizException(int code, String message) {
        super(message, null, false, false);
        this.code = ResultCode.businessCode(code);
    }

    public int getCode() {
        return code;
    }
}
```

> `super(message, null, false, false)` 的第三个参数是 `enableSuppression`，第四个是 `writableStackTrace`。设为 false 后 `getStackTrace()` 返回空数组，上面的测试由此通过。

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-common test -Dtest=BizExceptionTest
```

预期输出：

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: 跑一遍 oa-common 的全部测试，确认没有互相干扰**

```bash
cd oa && mvn -pl oa-common test
```

预期输出：

```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 6: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-common
git commit -m "feat(oa-common): 新增业务异常 BizException

不采集堆栈以避免高频业务校验下的性能开销，业务码强制落在 1000 以上"
```

---

## Task 5: 全局异常处理器

**Files:**
- Create: `oa/oa-framework/src/main/java/com/wenshan/oa/framework/web/GlobalExceptionHandler.java`
- Test: `oa/oa-framework/src/test/java/com/wenshan/oa/framework/web/GlobalExceptionHandlerTest.java`

> 测试使用 `MockMvcBuilders.standaloneSetup`，**不加载 Spring 上下文**，因此不需要数据库，跑得也快。

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-framework/src/test/java/com/wenshan/oa/framework/web/GlobalExceptionHandlerTest.java`：

```java
package com.wenshan.oa.framework.web;

import com.wenshan.oa.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("全局异常处理")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("业务异常 -> HTTP 400 且携带业务码")
    void handlesBizException() throws Exception {
        mockMvc.perform(get("/probe/biz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("部门不存在"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("参数校验失败 -> HTTP 400 且带上具体字段")
    void handlesValidationFailure() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    @DisplayName("未知异常 -> HTTP 500，且不把堆栈或原始消息泄露给调用方")
    void handlesUnknownException() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));
    }

    @Test
    @DisplayName("请求方法不支持 -> HTTP 405")
    void handlesMethodNotSupported() throws Exception {
        mockMvc.perform(post("/probe/biz"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    record ProbeRequest(@NotBlank(message = "name 不能为空") String name) {
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/biz")
        public String biz() {
            throw new BizException(1001, "部门不存在");
        }

        @PostMapping("/validate")
        public String validate(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("数据库连接池已耗尽，内部细节不应外泄");
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-framework test -Dtest=GlobalExceptionHandlerTest
```

预期：**编译失败**，报 `找不到符号: 类 GlobalExceptionHandler`。

- [ ] **Step 3: 实现 GlobalExceptionHandler**

创建 `oa/oa-framework/src/main/java/com/wenshan/oa/framework/web/GlobalExceptionHandler.java`：

```java
package com.wenshan.oa.framework.web;

import com.wenshan.oa.common.exception.BizException;
import com.wenshan.oa.common.result.Result;
import com.wenshan.oa.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * <p>
 * 原则：异常映射到真实的 HTTP 状态码，响应体的 {@code code} 承载业务语义。
 * 未知异常只回通用文案，堆栈与原始消息仅进日志，避免内部实现细节外泄。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
        log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ResultCode.PARAM_ERROR, "参数校验失败：" + detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ResultCode.PARAM_ERROR, "参数校验失败：" + detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败 {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ResultCode.PARAM_ERROR, "请求体格式错误，无法解析"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持 {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failure(ResultCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 未匹配到任何 handler 的路径。
     * <p>
     * 必须显式处理：否则会被下面的 {@code Exception} 兜底捕获，把 404 变成 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("路径不存在 {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ResultCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.SYSTEM_ERROR));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-framework test -Dtest=GlobalExceptionHandlerTest
```

预期输出：

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

若 `handlesUnknownException` 失败并提示消息是 `数据库连接池已耗尽...`，说明 `Exception` 兜底没生效——检查是否漏了 `@ExceptionHandler(Exception.class)`。

- [ ] **Step 5: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-framework
git commit -m "feat(oa-framework): 新增全局异常处理器

HTTP 状态码表达错误类别，响应体 code 承载业务语义；未知异常不泄露堆栈"
```

---

## Task 6: traceId 链路追踪

**Files:**
- Create: `oa/oa-framework/src/main/java/com/wenshan/oa/framework/web/TraceIdFilter.java`
- Modify: `oa/oa-framework/src/main/java/com/wenshan/oa/framework/web/GlobalExceptionHandler.java`（日志中带上 traceId）
- Test: `oa/oa-framework/src/test/java/com/wenshan/oa/framework/web/TraceIdFilterTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-framework/src/test/java/com/wenshan/oa/framework/web/TraceIdFilterTest.java`：

```java
package com.wenshan.oa.framework.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("traceId 过滤器")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("上游未传 traceId 时自动生成，并写回响应头")
    void generatesTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNotBlank());

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID)).isNotBlank();
    }

    @Test
    @DisplayName("上游传入 traceId 时沿用，保证跨服务链路连续")
    void reusesUpstreamTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader(TraceIdFilter.TRACE_ID, "upstream-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isEqualTo("upstream-abc-123"));

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID)).isEqualTo("upstream-abc-123");
    }

    @Test
    @DisplayName("请求结束后清理 MDC，避免线程复用导致串号")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("即使下游抛异常也必须清理 MDC")
    void clearsMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception ignored) {
            // 预期抛出
        }

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-framework test -Dtest=TraceIdFilterTest
```

预期：**编译失败**，报 `找不到符号: 类 TraceIdFilter`。

- [ ] **Step 3: 实现 TraceIdFilter**

创建 `oa/oa-framework/src/main/java/com/wenshan/oa/framework/web/TraceIdFilter.java`：

```java
package com.wenshan.oa.framework.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求绑定 traceId，写入 MDC 与响应头。
 * <p>
 * 上游（网关或调用方）若已带 {@code X-Trace-Id} 则沿用，保证跨服务链路连续。
 * <p>
 * <b>注意</b>：MDC 基于 ThreadLocal，只覆盖当前线程。后续引入 {@code @Async} 或线程池时，
 * 需要配套 {@code TaskDecorator} 传递 MDC，否则异步环节的日志会丢失 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-framework test -Dtest=TraceIdFilterTest
```

预期输出：

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: 让异常日志带上 traceId**

修改 `oa-framework/src/main/java/com/wenshan/oa/framework/web/GlobalExceptionHandler.java`，把未知异常的日志改为携带 traceId：

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常 traceId={}", MDC.get(TraceIdFilter.TRACE_ID), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.SYSTEM_ERROR));
    }
```

并在文件顶部的 import 区加入：

```java
import org.slf4j.MDC;
```

- [ ] **Step 6: 重跑异常处理与过滤器测试**

```bash
cd oa && mvn -pl oa-framework test
```

预期输出：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 7: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-framework
git commit -m "feat(oa-framework): 新增 traceId 链路追踪过滤器

沿用上游 X-Trace-Id 保证链路连续，finally 中清理 MDC 防止线程复用串号"
```

---

## Task 7: 多环境配置与敏感配置外置

**Files:**
- Create: `oa/oa-boot/src/main/resources/application.yml`
- Create: `oa/oa-boot/src/main/resources/application-dev.yml`
- Create: `oa/oa-boot/src/main/resources/application-test.yml`
- Create: `oa/oa-boot/src/main/resources/application-prod.yml`
- Create: `oa/oa-boot/src/main/resources/application-local.yml`（**不提交**）
- Test: `oa/oa-boot/src/test/java/com/wenshan/oa/SecretGuardTest.java`

- [ ] **Step 1: 写失败的测试（凭据守卫）**

创建 `oa/oa-boot/src/test/java/com/wenshan/oa/SecretGuardTest.java`：

```java
package com.wenshan.oa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("敏感配置守卫")
class SecretGuardTest {

    private static final Path RESOURCE_DIR = Path.of("src/main/resources");

    @Test
    @DisplayName("application*.yml 中不得出现明文密码，只能走占位符")
    void noPlaintextPasswordInTrackedConfig() {
        try (Stream<Path> files = Files.list(RESOURCE_DIR)) {
            List<Path> configs = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
                    // application-local.yml 是被 .gitignore 忽略的本机私密文件，明文口令是它的正常用法
                    .filter(path -> !path.getFileName().toString().contains("local"))
                    .toList();

            assertThat(configs).as("未找到任何 application 配置文件，检查路径 %s", RESOURCE_DIR.toAbsolutePath())
                    .isNotEmpty();

            configs.forEach(this::assertPasswordIsPlaceholder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertPasswordIsPlaceholder(Path config) {
        try {
            List<String> lines = Files.readAllLines(config);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.startsWith("password:")) {
                    continue;
                }
                String value = line.substring("password:".length()).trim();
                boolean safe = value.isEmpty() || value.startsWith("${");
                assertThat(safe)
                        .as("%s 第 %d 行出现明文密码：%s", config, i + 1, line)
                        .isTrue();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-boot test -Dtest=SecretGuardTest
```

预期：**FAIL**，报 `未找到任何 application 配置文件`。

- [ ] **Step 3: 创建公共配置**

创建 `oa/oa-boot/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: oa
  profiles:
    # 本地开发默认 dev；local 为个人私密覆盖（不入库），文件缺失时 Spring 静默跳过
    active: ${SPRING_PROFILES_ACTIVE:dev,local}
  jackson:
    default-property-inclusion: non_null
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai

server:
  port: 8090
  servlet:
    encoding:
      charset: UTF-8
      force: true

mybatis:
  configuration:
    map-underscore-to-camel-case: true
  type-aliases-package: com.wenshan.oa

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when_authorized

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%X{X-Trace-Id:-}] %logger{40} - %msg%n"
  level:
    com.wenshan.oa: info
```

- [ ] **Step 4: 创建 dev / test / prod 三个环境配置**

创建 `oa/oa-boot/src/main/resources/application-dev.yml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/${DB_NAME:oa}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&sslMode=PREFERRED&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true

mybatis:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

创建 `oa/oa-boot/src/main/resources/application-test.yml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/${DB_TEST_NAME:oa_test}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&sslMode=PREFERRED&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration
    clean-disabled: false
```

创建 `oa/oa-boot/src/main/resources/application-prod.yml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    # 生产环境不提供任何默认值，缺少环境变量时启动即失败，避免误连测试库
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&sslMode=REQUIRED
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true

logging:
  level:
    com.wenshan.oa: info
```

- [ ] **Step 5: 创建本机私密配置（不入库）**

创建 `oa/oa-boot/src/main/resources/application-local.yml`：

```yaml
# 本机私密配置，已被 .gitignore 忽略，禁止提交
spring:
  datasource:
    password: 123456
```

验证它确实被忽略：

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git check-ignore -v oa/oa-boot/src/main/resources/application-local.yml
```

以及（该行**必须**以 `.gitignore` 开头且退出码为 0，行号不重要）：

```
.gitignore:<行号>:oa/**/src/main/resources/application-local.yml	oa/oa-boot/src/main/resources/application-local.yml
```

**若该命令退出码非 0，说明忽略规则没生效，必须停下来修 .gitignore，绝不能让这个文件进版本库。**

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-boot test -Dtest=SecretGuardTest
```

预期输出：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 7: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-boot
git commit -m "feat(oa-boot): 多环境配置隔离与敏感配置外置

dev/test/prod 三套配置，密码一律走占位符；本机口令放 application-local.yml 并被 gitignore；
新增 SecretGuardTest 守卫，明文密码写入配置即构建失败"
```

---

## Task 8: 启动类与 Flyway 基线迁移

**Files:**
- Create: `oa/oa-boot/src/main/java/com/wenshan/oa/OaApplication.java`
- Create: `oa/oa-boot/src/main/resources/db/migration/V1__baseline.sql`
- Test: `oa/oa-boot/src/test/java/com/wenshan/oa/flyway/FlywayMigrationTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-boot/src/test/java/com/wenshan/oa/flyway/FlywayMigrationTest.java`：

```java
package com.wenshan.oa.flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"test", "local"})
@DisplayName("Flyway 迁移")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V1 迁移后 sys_config 表存在且结构符合预期")
    void sysConfigTableCreated() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_config' ORDER BY ORDINAL_POSITION",
                String.class);

        assertThat(columns).containsExactly(
                "id", "config_key", "config_value", "remark", "create_time", "update_time");
    }

    @Test
    @DisplayName("迁移历史中记录了 V1 版本且成功")
    void migrationHistoryRecorded() {
        Integer successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);

        assertThat(successCount).isEqualTo(1);
    }

    @Test
    @DisplayName("基线数据已写入")
    void baselineDataInserted() {
        String value = jdbcTemplate.queryForObject(
                "SELECT config_value FROM sys_config WHERE config_key = 'system.name'", String.class);

        assertThat(value).isEqualTo("OA系统");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-boot test -Dtest=FlywayMigrationTest
```

预期：**FAIL**，报 `找不到符号: 类 OaApplication`（因为 `@SpringBootTest` 需要定位启动类）。

- [ ] **Step 3: 创建启动类**

创建 `oa/oa-boot/src/main/java/com/wenshan/oa/OaApplication.java`：

```java
package com.wenshan.oa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OA 系统启动类。
 * <p>
 * 所有模块统一使用 {@code com.wenshan.oa} 根包，因此本类所在的包路径决定了组件扫描范围，
 * 新增模块时不要再改包名，否则模块内的 Bean 不会被扫描到。
 * <p>
 * 未使用 {@code @MapperScan}：它会将该包下所有接口注册为 MyBatis 映射器（包括 Service 接口）。
 * 约定改为在每个 Mapper 接口上标注 {@code @Mapper}。
 */
@SpringBootApplication
public class OaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OaApplication.class, args);
    }
}
```

- [ ] **Step 4: 创建 V1 基线迁移脚本**

创建 `oa/oa-boot/src/main/resources/db/migration/V1__baseline.sql`：

```sql
-- OA 系统基线迁移
-- 约定：表名与列名一律下划线命名，配合 mybatis.configuration.map-underscore-to-camel-case 映射到驼峰
-- 注意：本文件一经执行不得修改。已上线的迁移脚本只能新增 V2、V3，改历史脚本会导致校验失败。

CREATE TABLE sys_config
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    config_key   VARCHAR(100)    NOT NULL COMMENT '参数键',
    config_value VARCHAR(500)    NOT NULL COMMENT '参数值',
    remark       VARCHAR(200)             DEFAULT NULL COMMENT '备注',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统参数';

INSERT INTO sys_config (config_key, config_value, remark)
VALUES ('system.name', 'OA系统', '系统名称'),
       ('system.user.default-password', '', '新用户初始密码，由运维在部署时写入');
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-boot test -Dtest=FlywayMigrationTest
```

预期输出：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**如果这里报 `Unsupported Database: MySQL 9.7`**，说明 Boot 4.0.8 BOM 管理的 Flyway 11.14.1 不认 MySQL 9.7。在 `oa/pom.xml` 的 `<properties>` 中显式升级 Flyway：

```xml
<flyway.version>13.10.0</flyway.version>
```

然后重跑本步骤。**不要**用降级 MySQL 的方式绕过。

**如果报 `Public Key Retrieval is not allowed`**，说明 `application-test.yml` 的 URL 丢掉了 `allowPublicKeyRetrieval=true`，检查该参数是否被误删。

- [ ] **Step 6: 用 Flyway 的视角复核迁移结果**

```bash
docker exec mysql mysql -uroot -p123456 oa_test -e \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history;"
```

预期输出：

```
installed_rank  version  description  success
1               1        baseline     1
```

- [ ] **Step 7: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-boot
git commit -m "feat(oa-boot): 启动类与 Flyway 基线迁移

V1 建立 sys_config 系统参数表；约定迁移脚本一经执行不得修改，只能追加新版本"
```

---

## Task 9: MyBatis 接入与 sys_config 纵向切片

**Files:**
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/entity/SysConfig.java`
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/mapper/SysConfigMapper.java`
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/dto/SysConfigVO.java`
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/service/SysConfigService.java`
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/service/impl/SysConfigServiceImpl.java`
- Create: `oa/oa-system/src/main/java/com/wenshan/oa/system/controller/SysConfigController.java`
- Test: `oa/oa-boot/src/test/java/com/wenshan/oa/system/SysConfigApiTest.java`

> 这是**模板任务**：后续 8 个业务模块的 Controller / Service / Mapper / Entity 写法全部照抄本任务的分层与命名。

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-boot/src/test/java/com/wenshan/oa/system/SysConfigApiTest.java`：

```java
package com.wenshan.oa.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@DisplayName("系统参数接口")
class SysConfigApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("按 key 查询参数，返回统一返回体与驼峰字段")
    void getByKey() throws Exception {
        mockMvc.perform(get("/api/system/configs/system.name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.configKey").value("system.name"))
                .andExpect(jsonPath("$.data.configValue").value("OA系统"));
    }

    @Test
    @DisplayName("key 不存在时返回业务码 1001 而非 500")
    void getByMissingKey() throws Exception {
        mockMvc.perform(get("/api/system/configs/not.exists"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("参数不存在：not.exists"));
    }

    @Test
    @DisplayName("列表接口返回全部参数")
    void listAll() throws Exception {
        mockMvc.perform(get("/api/system/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-boot test -Dtest=SysConfigApiTest
```

预期：**FAIL**，返回 404（接口不存在），断言 `jsonPath("$.code")` 失败。

- [ ] **Step 3: 实现实体**

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/entity/SysConfig.java`：

```java
package com.wenshan.oa.system.entity;

import java.time.LocalDateTime;

/**
 * 系统参数，对应 sys_config 表。
 * <p>
 * 用普通 POJO 而非 record：MyBatis 需要无参构造 + setter 完成字段填充，可读性也更好排查。
 * 列名到驼峰（config_key -> configKey）依赖 mybatis.configuration.map-underscore-to-camel-case。
 */
public class SysConfig {

    private Long id;
    private String configKey;
    private String configValue;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
```

- [ ] **Step 4: 实现 Mapper**

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/mapper/SysConfigMapper.java`：

```java
package com.wenshan.oa.system.mapper;

import com.wenshan.oa.system.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 系统参数 Mapper。
 * <p>
 * 使用 {@code @Mapper} 单接口标注，不依赖 {@code @MapperScan} 扫包。
 */
@Mapper
public interface SysConfigMapper {

    @Select("SELECT id, config_key, config_value, remark, create_time, update_time "
            + "FROM sys_config ORDER BY id ASC")
    List<SysConfig> selectAll();

    /**
     * 按参数键查询。
     * <p>
     * 返回 {@code Optional} 而不是直接返回实体：MyBatis 直接返回单个实体时查不到会得到 null，
     * 容易在调用方漏判。包成 Optional 让"可能查不到"成为类型系统的一部分。
     */
    @Select("SELECT id, config_key, config_value, remark, create_time, update_time "
            + "FROM sys_config WHERE config_key = #{configKey}")
    Optional<SysConfig> selectByKey(String configKey);
}
```

- [ ] **Step 5: 实现返回给前端的 VO**

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/dto/SysConfigVO.java`：

```java
package com.wenshan.oa.system.dto;

import com.wenshan.oa.system.entity.SysConfig;

import java.time.LocalDateTime;

/**
 * 系统参数对外视图。
 * <p>
 * 与实体分离：实体映射数据库，VO 是对外契约。两者分离后，加字段、改字段名都不会影响对方。
 */
public record SysConfigVO(String configKey, String configValue, String remark, LocalDateTime updateTime) {

    public static SysConfigVO from(SysConfig entity) {
        return new SysConfigVO(entity.getConfigKey(), entity.getConfigValue(), entity.getRemark(), entity.getUpdateTime());
    }
}
```

- [ ] **Step 6: 实现 Service 接口与实现**

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/service/SysConfigService.java`：

```java
package com.wenshan.oa.system.service;

import com.wenshan.oa.system.dto.SysConfigVO;

import java.util.List;

public interface SysConfigService {

    List<SysConfigVO> listAll();

    SysConfigVO getByKey(String configKey);
}
```

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/service/impl/SysConfigServiceImpl.java`：

```java
package com.wenshan.oa.system.service.impl;

import com.wenshan.oa.common.exception.BizException;
import com.wenshan.oa.system.dto.SysConfigVO;
import com.wenshan.oa.system.mapper.SysConfigMapper;
import com.wenshan.oa.system.service.SysConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysConfigServiceImpl implements SysConfigService {

    /** 参数不存在。 */
    private static final int CODE_CONFIG_NOT_FOUND = 1001;

    private final SysConfigMapper sysConfigMapper;

    public SysConfigServiceImpl(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    @Override
    public List<SysConfigVO> listAll() {
        return sysConfigMapper.selectAll().stream().map(SysConfigVO::from).toList();
    }

    @Override
    public SysConfigVO getByKey(String configKey) {
        return sysConfigMapper.selectByKey(configKey)
                .map(SysConfigVO::from)
                .orElseThrow(() -> new BizException(CODE_CONFIG_NOT_FOUND, "参数不存在：" + configKey));
    }
}
```

- [ ] **Step 7: 实现 Controller**

创建 `oa/oa-system/src/main/java/com/wenshan/oa/system/controller/SysConfigController.java`：

```java
package com.wenshan.oa.system.controller;

import com.wenshan.oa.common.result.Result;
import com.wenshan.oa.system.dto.SysConfigVO;
import com.wenshan.oa.system.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/configs")
@Tag(name = "系统参数")
public class SysConfigController {

    private final SysConfigService sysConfigService;

    public SysConfigController(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    @GetMapping
    @Operation(summary = "查询全部系统参数")
    public Result<List<SysConfigVO>> list() {
        return Result.success(sysConfigService.listAll());
    }

    @GetMapping("/{configKey}")
    @Operation(summary = "按参数键查询")
    public Result<SysConfigVO> getByKey(@PathVariable String configKey) {
        return Result.success(sysConfigService.getByKey(configKey));
    }
}
```

> **安全提醒**：该接口当前无鉴权，属于骨架阶段的临时状态。系统参数可能含敏感值，《认证与权限》计划必须将其纳入角色控制。

- [ ] **Step 8: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-boot test -Dtest=SysConfigApiTest
```

预期输出：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 9: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-system
git commit -m "feat(oa-system): 新增 sys_config 纵向切片作为业务模块模板

Controller -> Service -> Mapper -> Entity 分层，对外用 VO 隔离实体；
Mapper 按 key 查询返回 Optional，避免调用方漏判 null"
```

---

## Task 10: OpenAPI 接口契约

**Files:**
- Test: `oa/oa-boot/src/test/java/com/wenshan/oa/docs/OpenApiDocsTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `oa/oa-boot/src/test/java/com/wenshan/oa/docs/OpenApiDocsTest.java`：

```java
package com.wenshan.oa.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@DisplayName("OpenAPI 接口文档")
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("api-docs 可访问且包含系统参数接口")
    void apiDocsAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OA 系统接口文档"))
                .andExpect(jsonPath("$.paths['/api/system/configs']").exists())
                .andExpect(jsonPath("$.paths['/api/system/configs/{configKey}']").exists());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd oa && mvn -pl oa-boot test -Dtest=OpenApiDocsTest
```

预期：**FAIL**，`jsonPath("$.info.title")` 断言失败（默认标题是 `OpenAPI definition`，不是 `OA 系统接口文档`）。

- [ ] **Step 3: 配置文档元信息**

在 `oa/oa-boot/src/main/resources/application.yml` 的 `springdoc` 段中追加：

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
  packages-to-scan: com.wenshan.oa
```

创建 `oa/oa-boot/src/main/java/com/wenshan/oa/config/OpenApiConfig.java`：

```java
package com.wenshan.oa.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI oaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("OA 系统接口文档")
                .description("前后端接口契约。前端按本文档并行开发，接口变更必须先改文档再改代码。")
                .version("1.0.0"));
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd oa && mvn -pl oa-boot test -Dtest=OpenApiDocsTest
```

预期输出：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add oa/oa-boot
git commit -m "feat(oa-boot): 接入 OpenAPI 接口文档

前后端按 /v3/api-docs 产出的契约并行开发，接口变更先改文档再改代码"
```

---

## Task 11: 协作规范（分支策略与提交约定）

**Files:**
- Create: `docs/CONTRIBUTING.md`

> 团队协作基建属于设计文档 §5.1「工程与协作基建」，且必须在多人并行开发前定下来，因此纳入本计划。

- [ ] **Step 1: 创建协作规范文档**

创建 `docs/CONTRIBUTING.md`：

```markdown
# 协作规范

## 分支策略

| 分支 | 用途 | 保护规则 |
|---|---|---|
| `main` | 随时可发布 | 禁止直接推送，只接受 PR |
| `develop` | 集成分支 | 禁止直接推送，只接受 PR |
| `feature/<期次>-<简述>` | 功能开发，如 `feature/phase0-auth` | 从 `develop` 切出 |
| `hotfix/<简述>` | 线上紧急修复 | 从 `main` 切出，修完同时合回 `main` 与 `develop` |

## 提交信息

格式：`<type>(<scope>): <描述>`

- type：`feat` / `fix` / `docs` / `test` / `refactor` / `build` / `chore`
- scope：模块名，如 `oa-common`、`oa-system`
- 描述用中文，说清"做了什么"，不写"修改了文件"

示例：`feat(oa-system): 新增员工档案多部门任职支持`

## PR 规则

- 至少 1 人评审通过才能合并
- 涉及 `oa-framework` 的改动（安全、异常、数据权限）需 2 人评审
- PR 描述必须写明：改了什么、为什么改、怎么验证
- 合并前必须通过 `cd oa && mvn clean test`
- **数据权限、枚举值、统一返回体的改动必须在 PR 描述中单独标出**——这三类影响面最大

## 纪律

- `application-local.yml` 永不提交
- 迁移脚本一经合并不得修改，只能追加新版本
- 接口变更先改 OpenAPI 文档，再改代码
```

- [ ] **Step 2: 提交**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add docs/CONTRIBUTING.md
git commit -m "docs: 新增协作规范，约定分支策略、提交信息格式与 PR 评审规则"
```

---

## Task 12: 端到端冒烟验证

**Files:** 无新增文件，纯验证。

- [ ] **Step 1: 跑全量测试**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo/oa && mvn clean test
```

预期输出：

```
[INFO] Reactor Summary for oa 1.0.0-SNAPSHOT:
[INFO] oa-common .......................................... SUCCESS
[INFO] oa-framework ....................................... SUCCESS
[INFO] oa-system .......................................... SUCCESS
[INFO] oa-boot ............................................ SUCCESS
[INFO] BUILD SUCCESS
```

四个模块必须全部 SUCCESS。任何一个失败都不要继续。

- [ ] **Step 2: 打包**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo/oa && mvn -DskipTests package
```

预期：`oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar` 生成，且体积明显大于普通 jar（含内嵌 Tomcat 与全部依赖）。

```bash
ls -lh oa/oa-boot/target/*.jar
```

- [ ] **Step 3: 前台启动应用**

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo/oa/oa-boot
java -jar target/oa-boot-1.0.0-SNAPSHOT.jar
```

预期启动日志中出现：

```
The following 1 profile is active: "dev"
...
Migrating schema `oa` to version "1 - baseline"
Successfully applied 1 migration to schema `oa`
...
Tomcat started on port 8090 (http)
Started OaApplication in ... seconds
```

**保持这个终端不关。**

- [ ] **Step 4: 另开终端验证四个端点**

```bash
echo "--- 参数接口 ---"
curl -s http://127.0.0.1:8090/api/system/configs/system.name
echo; echo "--- 列表 ---"
curl -s http://127.0.0.1:8090/api/system/configs
echo; echo "--- 业务异常（预期 400 + code 1001）---"
curl -s -w "\nHTTP=%{http_code}\n" http://127.0.0.1:8090/api/system/configs/not.exists
echo "--- 不存在的路径（预期 404，不能是 500）---"
curl -s -w "\nHTTP=%{http_code}\n" http://127.0.0.1:8090/api/not-exists
echo "--- 健康检查 ---"
curl -s http://127.0.0.1:8090/actuator/health
echo; echo "--- traceId 响应头 ---"
curl -s -D - -o /dev/null http://127.0.0.1:8090/api/system/configs/system.name | grep -i x-trace-id
```

预期输出：

```
--- 参数接口 ---
{"code":200,"message":"操作成功","data":{"configKey":"system.name","configValue":"OA系统","updateTime":"..."}}
--- 列表 ---
{"code":200,"message":"操作成功","data":[...]}
--- 业务异常（预期 400 + code 1001）---
{"code":1001,"message":"参数不存在：not.exists"}
HTTP=400
--- 不存在的路径（预期 404，不能是 500）---
{"code":404,"message":"资源不存在"}
HTTP=404
--- 健康检查 ---
{"status":"UP"}
--- traceId 响应头 ---
X-Trace-Id: 8f3c1a2b...
```

- [ ] **Step 5: 验证 traceId 确实进了日志**

回到 Step 3 的终端，日志行应形如（方括号内为非空 traceId）：

```
14:23:07.412 WARN  [8f3c1a2b9d5e4f0a8c7b6d5e4f3a2b1c] c.w.o.f.web.GlobalExceptionHandler - 业务异常 code=1001 message=参数不存在：not.exists
```

若方括号内为空，说明 logback pattern 里的 `%X{X-Trace-Id:-}` 与实际 MDC key 不一致。

- [ ] **Step 6: 验证 Swagger UI 可访问**

浏览器打开 `http://127.0.0.1:8090/swagger-ui.html`，应能看到「系统参数」标签下的两个接口。

- [ ] **Step 7: 停止应用并提交**

在 Step 3 的终端按 `Ctrl+C`，然后：

```bash
cd /Users/fengwenshan/wenshan/demo/java-demo
git add -A
git commit -m "chore(oa): 完成工程骨架端到端冒烟验证" --allow-empty
git log --oneline -12
```

预期输出（12 条提交，倒序；每个 Task 一条，可按 Task 回滚）：

```
chore(oa): 完成工程骨架端到端冒烟验证
docs: 新增协作规范，约定分支策略、提交信息格式与 PR 评审规则
feat(oa-boot): 接入 OpenAPI 接口文档
feat(oa-system): 新增 sys_config 纵向切片作为业务模块模板
feat(oa-boot): 启动类与 Flyway 基线迁移
feat(oa-boot): 多环境配置隔离与敏感配置外置
feat(oa-framework): 新增 traceId 链路追踪过滤器
feat(oa-framework): 新增全局异常处理器
feat(oa-common): 新增业务异常 BizException
feat(oa-common): 新增分页对象 PageQuery 与 PageResult
feat(oa-common): 新增统一返回体 Result 与标准错误码 ResultCode
build(oa): 建立独立 Maven reactor 与 common/framework/system/boot 四模块骨架
```

---

## 3. 完成标准（Definition of Done）

全部满足才算这份计划交付完成：

- [ ] `cd oa && mvn clean test` 四个模块全部 SUCCESS
- [ ] `mvn -DskipTests package` 产出可执行 jar
- [ ] 应用以 dev profile 启动，自动完成 Flyway 迁移到 `oa` 库
- [ ] `/api/system/configs/system.name` 返回 `code=200` 且中文不乱码
- [ ] 不存在的 key 返回 HTTP 400 与 `code=1001`，不是 500
- [ ] `/actuator/health` 返回 `UP`
- [ ] 响应头带 `X-Trace-Id`，且日志行内 traceId 非空
- [ ] `/swagger-ui.html` 可访问且包含「系统参数」两个接口
- [ ] `git check-ignore oa/oa-boot/src/main/resources/application-local.yml` 退出码为 0
- [ ] `SecretGuardTest` 通过（配置文件中无明文密码）
- [ ] `git log` 中每个 Task 一条独立提交，可按 Task 回滚

## 4. 交付后的下一步

本计划只覆盖第 0 期的第 1 份。按依赖顺序，第 0 期剩余 5 份计划为：

1. **认证与账号**（依赖本计划：需要 oa-framework 的异常体系与 oa-boot 的配置）
2. **组织架构与员工档案**（依赖认证：所有接口需要登录态）
3. **项目组与数据权限**（依赖组织架构：权限规则建立在部门与项目组之上）——**全项目风险最高的一份**
4. **审计与运维底座**（依赖数据权限：审计要记录权限判定结果）
5. **移动平台集成底座**（依赖认证：免登建立在账号绑定之上）

第 1 期（Flowable 审批引擎 + 请假试点线）在第 0 期全部完成后启动，不要提前并行——审批引擎的审批人规则依赖组织架构与项目组，地基未稳时开发会反复返工。
