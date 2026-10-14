# R07 数据字典 · 教学契约与里程碑设计

**日期**：2026-10-14
**状态**：已与用户逐项确认
**需求依据**：[R07 数据字典](../../requirements/R07-数据字典.md)（本文件不重复其内容）

---

## 0. 这份文档是什么

它**不是需求文档**。需求已经写完了，就是 R07。它也不是实施计划——R07 §3 已经把 Step 0–5 写清楚了。

它回答的是另一个问题：**用户是来学写代码的，不是来收货的。这件事怎么组织？**

用户的原话：

> 你不用写代码，教我写代码，写出优雅的代码、构造优雅工程结构。

本文档把这句话落成可执行的规则。它约束的是**助手的行为**，不是代码的形态。

---

## 1. 现状事实（本次摸底结果）

### 1.1 环境

| 项 | 现状 | 处方 |
|---|---|---|
| `JAVA_HOME` | `~/.zshrc:9` 指向**已删除**的 `jdk1.8.0_341` | 改指 `.../app/java/jdk/jdk-21.0.3.jdk/Contents/Home`（已实测 `java 21.0.3`） |
| `mvn` | **不在 PATH** | 加入 `.../app/java/apache-maven-3.9.15/bin` |
| `~/.m2/settings.xml` | **文件不存在** | 从 `.../app/java/maven-config/settings.xml` 复制 |
| MySQL | Docker `mysql:latest` = **9.7.0**，已运行 3 天，3306 已映射，`root/123456` 可登 | 直接用，缺一个库（见 §3.3） |
| 依赖源 | aliyun 镜像，`mirrorOf: *` | Boot **4.0.8** 与 MyBatis Starter **4.1.0** 已实测可拉取（HTTP 200） |

**顺带发现的两处陈旧配置**（M0 时一并处理，但**不是**本设计的交付物）：
`~/.zshrc:10` 把 `.` 追加进 `PATH`；`~/.zshrc:11` 的 `CLASSPATH` 引用 JDK 8 才有的 `tools.jar` / `dt.jar`。

### 1.2 工程

- 根 `pom.xml` 的 `<modules>` 里有 `05_spring-oa`，而根 pom 把 `spring-context` 显式锁在 **6.1.13**，
  Boot 4 用的是 Spring Framework 7 —— 继承根 pom 必冲突（[总体设计 §11](../../oa-system-design.md)）。
- `05_spring-oa/` 共 4 个文件：`Main.java` 是 IDEA 默认模板（未跟踪），
  `OaApplication.java` **已入暂存区**，`pom.xml` / `resources/` 未跟踪。
- 工作区另有 33 项未提交改动（01–04 的示例类被删除等）。
  **本设计不处理它们**，唯一例外是根 `pom.xml` 的 `<modules>` 要摘掉 `05_spring-oa`（见 §3.1）。

### 1.3 一个必须写明的限制

助手的文件沙箱只允许写本仓库目录，而 Maven 本地仓库在仓库之外
（`.../app/java/maven-config/repository`）。因此：

- **`mvn` 由用户在自己的终端执行**，助手不代跑。
- 助手若需要跑 Maven 验证，必须单独申请权限，用户可拒绝。

这与"一行代码都不替你写"的约定方向一致，不是缺陷。

---

## 2. 教学契约

### 2.1 循环

```
助手给：目标 + 接口契约 + 边界 + 陷阱（不出现在完整实现代码里）
  ↓
你写：自己写。卡住就问，但不要要代码 —— 要代码会被反问
  ↓
助手评审：正确性 → 边界 → 命名 → 分层有没有串
  ↓
你改 → 跑验证（curl / SQL / mvn test）→ 分步 git 提交
```

### 2.2 三条铁律

1. **助手只在两种情况下贴代码**：报错原文；以及"你写的东西编译不过"时的最小修正示意。
   其余场景一律用语言描述，由用户落笔。
2. **每个里程碑都有"完成的标志"**，全部来自 R07 §3，不是助手自造的。
   跑不出那个输出，不算过，也不进入下一个里程碑。
3. **卡住超过 10 分钟就问**。但助手先回答"去看哪一行 / 哪个概念"，不直接给答案。

### 2.3 助手明确不做的事

- 不写任何 `.java` / `.xml` / `.yml` / `.sql` 文件
- 不代跑 `mvn`、不代执行 `git commit`
- 不替用户做命名、包结构、字段类型这类决定 —— 只摆出取舍和推荐

---

## 3. 工程结构决策

### 3.1 代码住哪儿：新建 `oa/`，独立 Maven reactor（方案 A）

**决定**：新建 `oa/`，父 POM 用 `spring-boot-starter-parent`；
把 `05_spring-oa` 从根 `pom.xml` 的 `<modules>` 中移除，并删除该目录。

**理由（不是"文档这么写"）**：`05_` 这个前缀属于"按学习顺序编号的 demo 仓库"，
而 OA 是**要被部署的系统**。两者对 pom、对依赖版本、对发布节奏的要求完全不同。
混在一起，半年后没人分得清哪个 pom 该动。

**被否决的方案**：

| 方案 | 做法 | 否决理由 |
|---|---|---|
| B | 把 `05_spring-oa/` 就地改造成独立 reactor | 保住 IDEA 模块名，但"05 号 demo"和"独立系统"两种身份黏在一个目录名上 |
| C | 就在 `05_spring-oa` 里继承根 pom | Spring 6.1.13 与 Boot 4 冲突，等于先把地基挖了 |

### 3.2 版本与坐标

| 项 | 值 | 出处 |
|---|---|---|
| JDK | 21.0.3 | R01 §3 Step 1 |
| Maven | 3.9.15 | 同上 |
| Spring Boot | 4.0.8 | 同上 |
| MyBatis Starter | 4.1.0 | 同上 |
| 服务端口 | 8090 | R07 §3 Step 0 的验收命令 |
| Flyway | 由 Boot BOM 管理，不写版本 | R07 §3 Step 0 |

### 3.3 两个本设计要拍死的小决定

R07 没写、但会立刻卡住的两件事，现在定死，避免歧义：

1. **数据库名**：`oa`。（`05_spring-oa` 里那份配置写的是 `spring-oa`，带连字符，不用。）
   库**由用户手工创建**，不靠 `createDatabaseIfNotExist` 之类的开关。
2. **口令来源**：`application.yml` 里写 `${DB_PASSWORD:}` 占位，
   真实口令放 `application-local.yml` 并加进 `.gitignore`（R01 §3 Step 1 已规定）。

---

## 4. 里程碑 M0–M7

一张表就是实施计划。**R07 §3 是它的正文**，本表只加"你会学到什么"。

| # | 里程碑 | 产物 | 主要学到 | 完成的标志（出处） |
|---|---|---|---|---|
| **M0** | 修三个环境坑 | 改好的 `~/.zshrc`；`~/.m2/settings.xml` | 环境变量、PATH 查找顺序、Maven 本地仓库为何要与 IDEA 共用 | `java -version` = 21.0.3；`mvn -v` = 3.9.15 + 21.0.3（R01 §3 Step 1） |
| **M1** | `oa/` 独立 reactor | 根 `pom.xml` 摘掉 `05_spring-oa` 模块并删除该目录；新建 `oa/pom.xml`、`OaApplication.java`、`application.yml` + `application-dev.yml`、`application-local.yml`；建库 `oa`；`.gitignore` | 父 POM 与 BOM 的区别、`spring-boot-starter-parent` 干了什么、配置分层、`.gitignore` 与密钥 | `mvn spring-boot:run` 起得来，`curl :8090/actuator/health` = `{"status":"UP"}`（R07 §3 Step 0） |
| **M2** | 统一返回体与全局异常 | `Result`、`ResultCode`、`BizException`、`GlobalExceptionHandler` | 为什么必须在写业务前做；异常如何翻译成业务码；`@ExceptionHandler(Exception.class)` 吞掉 404 的坑 | 三条 curl：`BizException` → 400 + 业务码；不存在路径 → **404 不能是 500**；未知异常 → 500 且看不到堆栈（R01 §3 Step 2） |
| **M3** | Flyway + 建表 | `V1__init_dict.sql` | 迁移脚本为何不可变、Flyway 版本表、为何不用 `ddl-auto`、"组内唯一 ≠ 全局唯一" | 3 条手工 SQL **2 成功 1 失败**（R07 §3 Step 1） |
| **M4** | 只读链路 | `DictItemProvider` + `SysDictItemMapper` + `SysDictServiceImpl.listItems` + `SysDictController` | 分层的依赖方向（`system.dept → framework`，不是 `→ system.dict`）、MyBatis 映射、为何排序要 `sort, id` 两列、查询接口要对"空"宽容 | `GET /api/system/dicts/dept_type/items` 返回 3 条按 sort 排序；查不存在的字典返回 `[]` 不报错（R07 §3 Step 2） |
| **M5** | 写链路 | 6 个管理接口 + Service 校验 | 只停用不删除的纪律、`item_key` 不可改、正则即契约、唯一键冲突靠数据库兜底再翻译、**不写 delete 的 Mapper 方法** | 小写 key → 1505；改 `itemKey` 被拒；`DELETE` → 404（R07 §3 Step 3） |
| **M6** | 契约测试 | `DictContractTest` | 契约测试与单元测试的区别；没有外键就要有体检 | 故意插一条 `dict_type='not_exist'` 的字典项，第 2 条测试必须红（R07 §3 Step 4） |
| **M7** | 端到端验收 | 验收记录 + 分步提交 | "假配置"守门人（`data_scope` 必须搜不到）；提交粒度 | R07 §1.3 的 9 条全过；`git log` 看得到分步提交（R07 §3 Step 5、§4） |

**进度节奏**：用户选择不设中途审批点，一口气做到 M7。
助手仍按里程碑组织讲解与评审，每个里程碑结束时要求用户贴出"完成的标志"的实际输出。

---

## 5. 明确不做的事

- **不写实施计划文档**：R07 §3 已经是计划，其"完成的标志"就是验收标准。
  再写一份是重复，且两份文档一定会分叉。
- **不做视觉稿 / 架构图**：本阶段没有需要"看"才能答的问题。
- **不重构 01–04 与工作区里那 33 项未提交改动**：与本目标无关。
- **不引入 R07 §2.7 已排除的东西**：被引用检查、字典值类型校验、树形字典、多语言、版本管理、敏感字典权限。

---

## 6. 风险与已知缺口

| 风险 | 处置 |
|---|---|
| 用户起点是"地基类代码从未独立写过"，M0–M3 密度最高，可能卡住 | M0–M3 讲得最细；铁律 3 允许卡住就问 |
| Maven 依赖需联网拉取（本地仓库没有 Boot 4.0.8 / Flyway） | 已实测 aliyun 可拉；若网络不通，第一症状是 `Non-resolvable parent POM` |
| 助手无法代跑 Maven，验证只能靠用户贴输出 | 每个里程碑强制用户贴"完成的标志"原文 |
| 权限码机制（R03）与审计日志（R06）尚未存在，M5 的 6 个接口只校验登录 | R07 §2.5 已记为**已知临时缺口**，将来随 R03 Step 6 补上 |
| 生产库不应使用 root 账号 | R06 已记为上线前必办，本阶段用容器里的 root 不影响 |
