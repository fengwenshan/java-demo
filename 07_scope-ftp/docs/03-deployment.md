# 部署指南

服务端是一个可执行 Spring Boot Jar，数据都在本地目录（SQLite 元数据/审计 + 上传文件，**无需安装数据库服务**），
所以部署只需要「Jar + 一个可写数据目录」，下面是三种常用方式。

## 0. 前置条件与环境配置

| 方式 | 服务器要求 |
| --- | --- |
| Docker Compose（推荐） | 安装 Docker 24+ 与 docker compose 插件 |
| Jar + systemd | JRE 21（如 `apt install openjdk-21-jre-headless`）、可选 Nginx |
| 本地开发 | JDK 21（用 `scripts/mvn.sh`；JDK 路径可写在 `scripts/env.local`，Maven 自动查找） |

> 生产环境必须使用 HTTPS：客户端的签名头、AppSecret 都依赖传输层保护，
> 否则中间人可以抓包重放（nonce/时间戳只能限制重放窗口，不能防止窃听）。

### 环境配置：默认值 + 本地覆盖

入库的 `server/src/main/resources/application.yml` 自带一套可独立启动的默认值（端口 9090、
存储根 `./data`、审计 `./logs/audit`），但**客户端凭证没有默认值**（必须显式指定）；各 `application-<env>.yml` 只是本地/私有的
覆盖文件，**一律不入库**，没有它们时服务端直接用 `application.yml` 默认值启动：

| 环境 | 文件 | 端口 | 存储根 | 审计/运行日志 |
| --- | --- | --- | --- | --- |
| 本机开发①（默认） | `application-dev.yml` | 9090 | `./data` | `./logs/dev/` |
| 容器部署 | `application-docker.yml` | 8080 | `/app/data` | `/app/logs/` |
| 萧山生产 | `application-xiaoshan.yml`（私有） | 8080 | `/risen/soft` | `./logs/xiaoshan/` |
| 西湖生产 | `application-xihu.yml`（私有） | 8787 | `/risen/soft` | `./logs/xihu/` |

- 切换环境：`./scripts/run-server.sh --env <名字>`，或 `java -jar app.jar --spring.profiles.active=<名字>`；
  不指定时默认 `dev`；`application-dev.yml` 不存在时直接用 `application.yml` 默认值（9090、`./data`）。
  但**凭证必须指定**：启动时传 `--app-key` / `--app-secret`（Docker/systemd 仍可用环境变量注入），
  缺失时启动阶段直接失败（日志提示 `缺少 AppKey/AppSecret`）。
- 新增环境：复制 `application.yml` 为 `application-<env>.yml`，改端口/存储根/审计/日志 4 处，然后 `--env <名字>`（该文件不入库）。
- 客户端凭证在启动时通过 `--app-key` / `--app-secret` 传入；Docker/systemd 仍支持 `UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET` 环境变量注入（兼容旧部署）。
  其它配置写 `application.yml` 默认值或本地环境文件，不要再走环境变量。
- **客户端身份强制校验**：`app.security.client-identity-enforced`（默认 `false`，dev/萧山/西湖环境文件已开启）。
  开启后所有 `/api/**`（含 `/api/health`）都必须带 `X-Device-Mac`，缺 MAC/IP、命中 `client_blacklist_ip` /
  `client_blacklist_mac` 任一表、或 activate 前没有近期 verify 都会被 403 并写入黑名单。
  因此：① 探活/监控必须带固定合法 MAC（docker-compose healthcheck 已带）；② 先发布新版客户端再重启服务端，
  旧客户端第一条请求就会被封；③ 解封需手动删默认根 `metadata.sqlite3` 里的黑名单行（见 `docs/04-security.md` 2.8）。

## 1. 方式 A：Docker Compose（推荐）

```bash
cd 07_scope-ftp/deploy
cp .env.example .env
# 必改：UPLOAD_APP_SECRET 换成 32 字节以上随机串
#      openssl rand -hex 32
vim .env

docker compose up -d --build
docker compose ps
curl -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:8080/api/health
```

> 开启 `client-identity-enforced` 后健康检查也必须带 `X-Device-Mac`；不带头会被当成缺身份客户端封禁
> （compose 里的 healthcheck 已带固定值）。（用户上传的文件/目录 + `.upload-meta/metadata.sqlite3`）；`deploy/logs/`（审计 JSONL/`audit.sqlite3` + 运行日志）
- 首次启动前确保容器用户（uid 1001）可写：`mkdir -p deploy/data deploy/logs && sudo chown -R 1001:1001 deploy/data deploy/logs`
- 升级：`git pull && docker compose up -d --build`，数据与日志不受影响
- 查看日志：`docker compose logs -f server`
- 客户端「设置」里的服务器地址填 `http(s)://你的域名或IP:8080`（前面如果加了 Nginx，就填 Nginx 地址，不带端口）

多存储根直接写在对应环境文件的 `app.storage.roots[]` 里（每项 `id/name/path`，第一个为默认根；例如 `application-docker.yml` 已配置 `/app/data`）：

```yaml
app:
  storage:
    roots:
      - id: work
        name: 工作资料
        path: /app/data/work
      - id: media
        name: 媒体库
        path: /app/data/media
```

多根只通过环境文件里的 `app.storage.roots[]` 配置；`UPLOAD_STORAGE_ROOTS` 环境变量覆盖已删除，`roots` 为空时服务端启动失败。

`deploy/.env` 只需要 5 个变量（其余配置都在 `application-docker.yml` 里）：

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 容器内环境（jar 内 `application-<env>.yml`） | `docker` |
| `UPLOAD_APP_KEY` | 客户端 appKey | 无，必填（缺失容器启动失败） |
| `UPLOAD_APP_SECRET` | 客户端 appSecret（**必须修改**） | 无，必填（缺失容器启动失败） |
| `SERVER_PORT` | 宿主机端口（容器内固定 8080） | `8080` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |

大小上限、分片、限流、终端超时等全环境通用参数在 `application.yml` 里，改完重新构建镜像即可。

### 自定义存储目录

用户上传的文件直接存放在**存储根目录**内（即环境文件里的 `app.storage.roots[]`）；内部元数据放 `<root>/.upload-meta/`，multipart 临时文件放系统临时目录：

```bash
# Docker：容器内存储根是 /app/data，宿主机对应 deploy/data/（挂载见 docker-compose.yml）
# 裸机 systemd：在 jar 内 application-<env>.yml 里改 roots[].path，
# 并同步 /etc/systemd/system/scop-ftp.service 的 ReadWritePaths（例如 /data/scop-ftp）
```

目录结构为：

```text
<存储根目录>/
├── docs/            用户自己的目录（示例）
├── 报告.pdf          用户上传的文件（原名落盘，重名自动加序号）
└── .upload-meta/    内部元数据（隐藏）：metadata.sqlite3（旧 files.json/devices.json 自动迁移备份）
```

配置多个 `app.storage.roots[]` 时，每个根目录都是这样的结构（各自有 `.upload-meta/`），备份/挂载需要覆盖所有根；每个环境文件各配自己的根（dev 是 `./data`、docker 是 `/app/data`）。

不再创建 `files/`、`meta/`、`tmp/` 目录，也**不再自动迁移旧单根布局**：旧 `meta/` 与 `.upload-meta/` 语义不同，升级旧数据需要手动整理（把旧 `files/` 内容放进新根目录、必要时按新 `storedPath` 语义迁移元数据）；旧 `db/` 与用户文件不动。

服务端启动时会解析绝对路径、创建目录并校验可写；不可写会直接启动失败。
**所有文件读写删除都被限制在该目录内**：客户端只能指定相对 root 的已存在目录/路径，metadata 中被篡改成 `../`、绝对路径或指向目录外的符号链接都会被拒绝。

## 2. 方式 B：Jar + systemd + Nginx

适合不想装 Docker 的云服务器（Debian/Ubuntu/CentOS 均可）。

### 2.1 获取构建产物

不再在本地打包。推送 `v*` tag 后由仓库根目录的 `.github/workflows/scope-ftp.yml` 构建，在 Actions 的 **ScopeFTP-Server-package** artifact 中下载部署 zip；默认始终有 `ScopeFTP-Server.zip`（内部固定 `ScopeFTP-Server/` + `ScopeFTP-Server.jar` + `start.sh` + `scop-ftp.env`），私有配置齐全时额外有 `ScopeFTP-Server-xiaoshan.zip` / `ScopeFTP-Server-xihu.zip`。普通 `main` push / PR / 手动发布 Release 不触发构建。

### 2.2 上传与安装

```bash
# 服务器上
sudo useradd -r -s /usr/sbin/nologin scopftp
sudo mkdir -p /opt/scop-ftp/data
sudo chown -R scopftp:scopftp /opt/scop-ftp

# 本地（下载 Actions 的 ScopeFTP-Server-package artifact 并解压 ScopeFTP-Server.zip）
unzip -q ScopeFTP-Server.zip
scp ScopeFTP-Server/ScopeFTP-Server.jar root@SERVER:/opt/scop-ftp/app.jar
scp deploy/scop-ftp.service root@SERVER:/etc/systemd/system/

# 服务器上，写环境文件（权限 600，含环境名与密钥）
sudo tee /opt/scop-ftp/scop-ftp.env >/dev/null <<'EOF'
SPRING_PROFILES_ACTIVE=xiaoshan
# 可选：服务器有多个 Java 版本时指定 JDK 21 路径；不写则用 /usr/bin/java
# JAVA_HOME=/opt/jdk-21
UPLOAD_APP_KEY=你的appKey
UPLOAD_APP_SECRET=请替换为随机长密钥
JAVA_OPTS=-Xms256m -Xmx512m
EOF
sudo chmod 600 /opt/scop-ftp/scop-ftp.env
sudo systemctl daemon-reload
sudo systemctl enable --now scop-ftp
curl -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:8080/api/health
```

### 2.2.1 指定 JDK（服务器/本机有多个 Java 版本）

项目按 Java 21 编译，运行的 JVM 必须是 21+。服务器上装了多个 Java 版本时，**用 `JAVA_HOME` 指定要用的那一个**，不用改系统默认。
本机不需要每次 `export`：把 JDK 路径写进环境配置文件即可（`scripts/env` / `scripts/env.local` / 按环境变体），
`scripts/mvn.sh`、`scripts/run-server.sh`、`deploy/build-server-profiles.sh` 都会自动读取。**Maven 不用配置**：脚本优先用 PATH 里的 `mvn`，找不到时回退本机 IntelliJ 自带 Maven。

本机选择顺序：**shell 显式 `JAVA_HOME` > 环境配置文件里的 `JAVA_HOME` > 自动探测**（macOS `/usr/libexec/java_home -v 21`、
Linux `/usr/lib/jvm/*21*`、`/opt/jdk-21`、PATH 里的 java 21）> 本机 IntelliJ 自带 JDK 兜底。
`JAVA_HOME` 不可用或低于 21 时会自动尝试下一个候选，全部失败才报错；找到可用 JDK 后静默使用。

环境配置文件按 profile 从低到高加载（后者覆盖前者）：

```text
scripts/env → scripts/env.local → scripts/env.<profile> → scripts/env.local.<profile>
```

| 场景 | 命令 | 生效优先级（高 → 低） |
| --- | --- | --- |
| 启动 dev | `./scripts/run-server.sh --env dev`（不传默认也是 dev） | `env.local.dev` > `env.dev` > `env.local` > `env` |
| 打包 xihu | `./deploy/build-server-profiles.sh xihu` | `env.local.xihu` > `env.xihu` > `env.local` > `env` |
| 直接 Maven 打包 | `SCOP_FTP_ENV=xihu ./scripts/mvn.sh -f server/pom.xml ...` | 同上（按 `xihu`） |
| 不带 profile | `./scripts/mvn.sh ...` | `env.local` > `env` |

- 文件名含 `local` 的环境配置为本机私有（`.gitignore` 忽略），其余 `env[.<profile>]` 可按需入库；
- 不存在的文件自动跳过，不需要每个环境都建；
- 确认实际加载了哪些：`SCOP_FTP_VERBOSE_ENV=1 ./scripts/run-server.sh --env dev`（输出到 stderr）。

| 部署方式 | 配置位置 |
| --- | --- |
| systemd / 分环境 zip 包 | 环境文件 `scop-ftp.env` 里加一行 `JAVA_HOME=/opt/jdk-21` |
| `deploy/deploy.sh remote` | `deploy/.env.deploy` 里配 `REMOTE_JAVA_HOME=/opt/jdk-21`，脚本写入远端 `scop-ftp.env` |
| 本地开发 / 本地打包 | `scripts/env.local` 里写 `JAVA_HOME=/opt/jdk-21`；一次性临时覆盖仍可用 `JAVA_HOME=/opt/jdk-21 ./scripts/run-server.sh` |
| Docker Compose | 镜像内已固定 JRE 21，不需要也不支持 `JAVA_HOME`；要换 JDK 版本需改 `deploy/Dockerfile` 的基础镜像 |

- systemd 单元通过 `/bin/sh -c` 读取环境文件里的 `JAVA_HOME`：有值就用 `$JAVA_HOME/bin/java`，没有就用 `/usr/bin/java`。
- 分环境包里的 `start.sh` 同样读取 `scop-ftp.env` 的 `JAVA_HOME`；`JAVA_HOME` 路径不对或 Java 版本低于 21 时会**直接报错退出**，不会等到 `UnsupportedClassVersionError`。
- 打包时 `build-server-profiles.sh` 会按上面的优先级读取该 profile 的生效配置，直接生成包内 `scop-ftp.env`（不再生成 `.env.example`），并用其中的 `JAVA_HOME` / `JAVA_OPTS` 作为 `start.sh` 的默认值。不传 profile 时生成默认包，不激活 Spring profile，直接使用 `application.yml`；产物名固定为 `release/server/ScopeFTP-Server/` 和 `ScopeFTP-Server.zip`，包内 Jar 固定为 `ScopeFTP-Server.jar`。
- 验证指定是否生效：

  ```bash
  /opt/jdk-21/bin/java -version          # 先确认路径存在且是 21+
  pgrep -af 'scop-ftp/app.jar'           # 看进程命令行开头的 java 路径是否是 /opt/jdk-21/bin/java
  source scripts/env.sh; echo "$JAVA_HOME"  # 本机：看脚本最终选中的 JDK
  sudo journalctl -u scop-ftp -n 20 --no-pager | grep -i "Tomcat started"
  ```

### 2.3 生产环境与私有配置

两个生产环境各一个完整文件，互不覆盖：

- `application-xiaoshan.yml`：端口 8080，存储根 `/risen/soft`，审计 `./logs/xiaoshan/audit`、运行日志 `./logs/xiaoshan/`（私有）
- `application-xihu.yml`：端口 8787，存储根 `/risen/soft`，审计 `./logs/xihu/audit`、运行日志 `./logs/xihu/`（私有）

所有 `application-<env>.yml` 环境文件（dev/docker/xiaoshan/xihu）都不入库，已在 `.gitignore` 中用
`server/src/main/resources/application-*.yml` 过滤；仓库只入库 `application.yml`（自带可启动默认值）。
CI 里生产配置由 GitHub Secrets 注入后再构建。本地保留自己的副本即可，不要 `git add`。

**GitHub Secrets**（`Settings → Secrets and variables → Actions`）：

| Secret | 内容 |
| --- | --- |
| `APPLICATION_XIAOSHAN_YML_BASE64` | `base64 -i server/src/main/resources/application-xiaoshan.yml` 的输出 |
| `APPLICATION_XIHU_YML_BASE64` | 同上，xihu |
| `UPLOAD_CLIENT_APP_KEY` / `UPLOAD_CLIENT_APP_SECRET` | 可选，客户端构建时内置的 AppKey/AppSecret |
| `UPLOAD_CLIENT_SERVER_URL` | 可选，客户端默认服务端地址 |

如果未配置两个生产 profile Secrets，CI 仍会生成默认 `ScopeFTP-Server.zip`（使用 jar 内 `application.yml`），但不会生成分环境部署包；两个都齐全后会额外把固定名 `ScopeFTP-Server.zip` 复制成 `ScopeFTP-Server-xiaoshan.zip` / `ScopeFTP-Server-xihu.zip` 上传。

分环境包里的 `start.sh` 已自动带 `--spring.profiles.active=<profile>`（default 包不带 profile，使用 `application.yml`），默认后台运行，日志写入包内 `logs/console.log`；解压后目录固定为 `ScopeFTP-Server/`，Jar 固定为 `ScopeFTP-Server.jar`：

```bash
APP_SECRET="$(openssl rand -hex 32)"
./start.sh --app-key=xxx --app-secret="$APP_SECRET"   # 后台启动，返回后服务继续运行
./start.sh logs         # 跟随日志；Ctrl+C 只退出日志，不会停服务
./start.sh status
./start.sh stop
./start.sh restart
./start.sh run          # 前台启动，调试用
```

`--app-key` / `--app-secret` 是**必传凭证**，启动时直接传参，不写入包内 `scop-ftp.env`；其余参数可选（方括号表示可选）：

```bash
./start.sh --app-key=xxx --app-secret="$APP_SECRET" [--server.port=xxx] [--spring.profiles.active=xxx] [其它 Spring 参数...]
./start.sh --app-key=my-key --app-secret="$APP_SECRET"          # 前台
./start.sh start --app-key=my-key --app-secret="$APP_SECRET"    # 后台
```

凭证没有内置默认值：启动命令没传 `--app-key` / `--app-secret` 时，`start.sh` 会直接报错；
直接 `java -jar` 也会在启动前打印一次性提示并退出，不会先拉起完整 Spring 再刷堆栈。
AppSecret 任意非空字符串即可，不是只能填 32 字节（少于 32 字符服务端只警告、不阻止启动），
生产推荐 `openssl rand -hex 32`。

临时换密钥（每次启动都传参数；直接传参数时前台运行，后台运行请用 `./start.sh start ...`）：

```bash
# 直接启动 Jar：简写参数会被映射到第一个预配发客户端
APP_SECRET="$(openssl rand -hex 32)"
java -jar ScopeFTP-server-1.0.0.jar --spring.profiles.active=xiaoshan --app-key my-key --app-secret "$APP_SECRET"

# 分环境包里的 start.sh 也支持
./start.sh --app-key my-key --app-secret "$APP_SECRET"

# 或完整 Spring 属性
./start.sh --app.security.clients[0].app-key=my-key --app.security.clients[0].app-secret="$APP_SECRET"
```

### 2.4 Nginx + HTTPS

```bash
sudo cp deploy/nginx.conf /etc/nginx/conf.d/scop-ftp.conf
# 修改 server_name / 证书路径（/etc/nginx/certs/）
sudo nginx -t && sudo systemctl reload nginx
```

证书可用 Let's Encrypt：

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d upload.example.com
```

#### Nginx 子路径转发

如果同一个域名下要挂多个项目，例如 `https://example.com/scopeftp/`，必须让 Nginx **剥掉项目前缀**，把 `/scopeftp/api/...` 转成后端认识的 `/api/...`：

```nginx
location /scopeftp/ {
    # 注意 proxy_pass 末尾的 /，它会把 /scopeftp 前缀替换掉
    proxy_pass http://scop_ftp_backend/;

    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection        "";

    proxy_request_buffering off;
    proxy_buffering         off;
    proxy_read_timeout      300s;
    proxy_send_timeout      300s;
}
```

客户端「服务器地址」填：

```text
https://example.com/scopeftp
```

不要写成 `proxy_pass http://scop_ftp_backend;`（末尾没有 `/`），否则后端收到的是 `/scopeftp/api/...`，签名校验和路由都会失败。

另外确认 `upstream` 端口与环境文件一致：`dev` 是 `9090`，`docker` / `xiaoshan` 是 `8080`，`xihu` 是 `8787`。端口写错时 Nginx 会返回 502，客户端显示「服务器内部错误」。

### 2.5 一键 remote 部署（可选）

在 `deploy/.env.deploy` 中配置：

```bash
SERVER_HOST=1.2.3.4
SERVER_USER=root
SERVER_PORT=22                 # SSH 端口
REMOTE_DIR=/opt/scop-ftp
SERVER_PROFILE=xiaoshan        # 环境名：jar 内 application-<env>.yml，默认 xiaoshan
APP_PORT=8080                  # 服务端端口，用于部署后的健康检查
REMOTE_JAVA_HOME=/opt/jdk-21   # 可选：服务器上要用的 JDK 21 路径；不填用 /usr/bin/java
UPLOAD_APP_KEY=你的appKey
UPLOAD_APP_SECRET=请替换为随机长密钥
```

然后：

```bash
./deploy/deploy.sh remote
```

把 GitHub Actions 下载的 `ScopeFTP-Server-package` 里的 `ScopeFTP-Server.zip` 解压，将其中的 `ScopeFTP-Server/ScopeFTP-Server.jar` 放到 `server/target/`，或设置 `JAR_PATH` 指向该文件；脚本会 rsync 到服务器、生成 600 权限环境文件、重启 systemd 并做健康检查，不再本地打包。

### 2.6 多级转发（relay）部署

relay 配置默认关闭，避免所有节点默认成为代理。本地 `scripts/run-server.sh` 和分包 `start.sh` 会默认注入 `--app.relay.enabled=true`；直接 `java -jar`、systemd、Docker 等部署方式需要显式开启：

```yaml
app:
  relay:
    enabled: true                  # 中转节点显式开启；纯数据节点保持 false
    max-hops: 16
    max-chain-header-bytes: 16384
    max-via-header-bytes: 8192
    connect-timeout-ms: 3000
    request-timeout-ms: 20000
    upload-timeout-ms: 180000
    allow-private-network: true   # 内网 B/C 通常需要；公网 A→公网 B 可关
    allow-loopback: false         # 生产不要开
    allowed-hosts: []             # 可选：["b.internal","10.0.0.5:8080"]
```

部署拓扑建议：

- 客户端只直连 A；B/C 只允许 A（或上一跳）访问，不要在客户端配置里直连 B/C，也不要把 B/C 暴露给公网。
- A/B 作为中转节点需要开启（本地 `run-server.sh`/分包 `start.sh` 已自动注入；直接 jar/systemd/Docker 需显式配置）；最终目标 C 的数据接口可以仍是旧版（A/B 会按正常 path 转发），但 C 要记录来源链/`relayVia` 需要升级本版本。
- 旧客户端读到 v3 config 可能把 relay 当 direct 直连 B/C，从而绕过 A；发布窗口内限制 B/C 可达性、轮换 B/C 凭据，或强制升级客户端。
- `allowed-hosts` 非空时只允许列出的 host（或 `host:port`）；默认拒绝 loopback/link-local/云元数据地址，`allow-private-network` 默认 true（内网转发需要）。
- 反向代理子路径：`serverUrl` 可带子路径（如 `https://example.com/scopeftp`），relay 转发会保留该子路径后再追加 `/api/relay` / 原 path。

审计：中转节点会记录 `relay.*` action 与 `relayVia` 来源链（JSONL + SQLite `relay_via` 列）；旧审计库启动时自动 `ALTER TABLE` 补列，无需手工迁移。

## 3. 方式 C：本地开发运行

```bash
cd 07_scope-ftp
source scripts/env.sh
./scripts/mvn.sh -f server/pom.xml spring-boot:run
# 或（首次会构建一次本地 Jar；发布包仍以 Actions 产物为准）
./scripts/run-server.sh                       # 默认 dev 环境（9090，./data）
./scripts/run-server.sh --server.port=8081    # 端口被占用时临时换端口
```

环境文件改动（新增/修改 `application-*.yml`）会被脚本自动检测并重新打包；也可以显式加 `--build` 强制重新打包：

```bash
./scripts/run-server.sh --build
```

客户端开发：

```bash
cd client
pnpm install --store-dir ../.cache/pnpm-store
pnpm tauri dev        # 桌面客户端
pnpm dev              # 仅浏览器预览界面（接口不可用）
```

构建缓存统一放在 `.cache/`。本地只用于开发/测试，不再生成安装包；完整安装包由 GitHub Actions 生成：

```bash
cd client
pnpm install --store-dir ../.cache/pnpm-store
pnpm build
pnpm tauri dev
cd src-tauri && cargo check && cargo test
```

发布产物（macOS DMG / Windows 免安装 exe 与 NSIS `.exe` / 服务端 `ScopeFTP-Server.zip` 与可选分环境 zip）见 Actions 页面下载。

### 真实服务端联调测试

服务端启动后，可让**客户端 Rust 代码**直接打真实接口（验证签名、multipart 上传、下载校验、删除全链路）：

```bash
cd 07_scope-ftp
./scripts/live-client-test.sh
# 输出：健康检查 → 签名校验 → 设备激活 → 上传（中文文件名）→ 列表 → 下载校验 → 删除 → 删除后 404
```

也可以指定远程服务端：

```bash
LIVE_E2E_BASE_URL=https://upload.example.com ./scripts/live-client-test.sh

# 服务端改过密钥时，在客户端「设置」里填同样的 AppKey/AppSecret（留空则用内置默认）。
# 也可以构建时用 UPLOAD_CLIENT_APP_KEY=... UPLOAD_CLIENT_APP_SECRET=... 覆盖默认值。
```

## 4. 部署后验证

```bash
# 1) 健康检查（免签；开启身份强制后需带 X-Device-Mac）
curl -s -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:9090/api/health

# 2) 端到端签名测试（本机 dev 端口 9090；e2e 脚本默认 8080，需显式 BASE_URL）
#    扩展用例含分片/续传/搜索，令牌消耗较大；默认容量 1000 一般够用
BASE_URL=http://127.0.0.1:9090 node scripts/e2e.mjs
# 需要测试「按目录上传」时可显式指定本机存储根：
# E2E_STORAGE_ROOT=/app/data node scripts/e2e.mjs

# 3) 连接远程服务器
BASE_URL=https://upload.example.com \
APP_KEY=你的appKey APP_SECRET=你的密钥 \
node scripts/e2e.mjs
```

成功输出示例：

```text
=== 结果：55 通过，0 失败 ===
```

### 本地连接失败（网络错误 / 空响应）

先确认端口到底被谁占用：

```bash
lsof -nP -iTCP:9090 -sTCP:LISTEN
curl -v -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:9090/api/health
```

macOS 上 Java 监听 IPv6 `*:9090` 时，另一个前端项目的 Vite 可能同时监听 IPv4 `127.0.0.1:9090`：客户端用 `127.0.0.1` 会先连到 Vite，表现为 `error sending request` 或空响应，但服务端日志看起来正常。释放该端口，或换端口启动并同步修改客户端地址：

```bash
./scripts/run-server.sh --server.port=8081
# 客户端「设置 → 服务器地址」改成 http://127.0.0.1:8081
```

### 审计日志查询失败（no such table: audit_log）

如果服务运行期间删除/替换了当前环境审计目录（环境文件里的 `app.audit.dir`）下的 `audit.sqlite3`，SQLite 会新建一个空文件，但不会自动建表；旧实例查询时会报 `SQLITE_ERROR: no such table: audit_log`，接口返回「服务器内部错误：审计日志查询失败」。

- 新版 `AuditStore` 检测到该错误会自动重建 schema 并重试；重启服务也可恢复。
- 清空审计数据建议：停止服务 → 删除该目录下 `audit-*.jsonl` 和 `audit.sqlite3` → 重新启动。不要在服务运行中只删 SQLite 文件。
- 客户端请求失败统一走顶部全局 `message` 提示，日志/目录加载失败不再在页面内嵌错误 Alert。

## 5. 运维要点

- **备份**：定期备份所有存储根目录（用户文件 + 隐藏的 `.upload-meta/metadata.sqlite3`）与当前环境审计目录（环境文件里的 `app.audit.dir`，JSONL + `audit.sqlite3`）。SQLite 运行中直接 `cp` 可能丢 WAL，建议 `sqlite3 metadata.sqlite3 ".backup backup.sqlite3"`（审计库同理），或停服后整目录备份。
- **磁盘**：上传文件不自动清理，建议加 cron 清理或接入对象存储（后续可替换 `FileStorageService`）。
- **日志**：stdout + 环境文件 `logging.file.name` 指定的文件；操作审计默认开启，JSONL 与 `audit.sqlite3` 都在 `app.audit.dir`，记录客户端全部 `/api/**` 操作（含失败），JSONL 按天、默认保留 90 天，SQLite 同步清理；字段含 requestId/action/path/参数/耗时/状态、`relayVia` 来源链，敏感字段脱敏；客户端日志抽屉把操作与连接日志合并展示，可按类型/操作/时间筛选。
- **密钥轮换**：修改启动参数里的 `--app-secret`（Docker 改 `.env`）后重启服务，同时更新客户端「设置」里的 AppSecret；旧密钥立即失效。
- **JDK**：服务器有多个 Java 版本时，在 `scop-ftp.env` 里用 `JAVA_HOME=/opt/jdk-21` 指定；分环境包 `start.sh` 会校验该路径和版本（必须 21+），日志里启动成功的标志是 `Tomcat started on port ...`。`JAVA_HOME` 留空或没有这一行时，`start.sh` 会使用启动前 shell 的 `JAVA_HOME`，没有则用 `PATH` 里的 `java`。
- **端口**：仅暴露 Nginx 的 443；服务端口（dev 9090 / docker、xiaoshan 8080 / xihu 8787）不要直接暴露公网（云安全组限制内网）。
- **健康检查**：`GET /api/health` 免签，可接入负载均衡/监控。
- **时区**：签名时间戳是 epoch 毫秒，与服务器时区无关；只要客户端时钟准确即可。
