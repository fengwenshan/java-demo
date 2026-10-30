# ScopeFTP-client · 桌面客户端

Tauri 2 + React 18 + TypeScript + Vite 6 + Ant Design 5 桌面客户端，产品名 `ScopeFTP`，安装包/二进制名 `ScopeFTP-client`。
所有网络请求、HMAC-SHA256 签名、文件读写都在 Rust 侧完成，WebView 拿不到 `appSecret`。

功能包括：FTP 式目录浏览/上传/下载/删除、拷贝移动、在线编辑（Monaco）、zip 解压、远程终端，以及顶栏日志抽屉（操作/连接日志合并视图，类型/操作/成功失败/时间筛选、服务端分页、详情查看）。

## 技术栈

| 层 | 版本 |
| --- | --- |
| Tauri（Rust crate） | 2.12.x |
| @tauri-apps/cli / api | 2.12.4 |
| @tauri-apps/plugin-dialog | 2.8.4 |
| React / react-dom | 18.3.1 |
| TypeScript | 5.9.3 |
| Vite / @vitejs/plugin-react | 6.4.5 / 4.7.0 |
| antd / @ant-design/icons | 5.29.3 / 5.6.1 |

## 开发与构建

构建缓存使用项目内 `.cache/`，本地只用于开发与测试；发布安装包由仓库根目录的 GitHub Actions 生成（见 [../docs/05-ci.md](../docs/05-ci.md)）。

```bash
source ../scripts/env.sh
pnpm install --store-dir ../.cache/pnpm-store
pnpm build                 # tsc + vite build
pnpm tauri dev             # 桌面客户端开发模式
pnpm tauri build --debug --no-bundle   # 只编译不打包
pnpm tauri build --bundles app         # macOS 本地调试产物
```

Rust 校验：

```bash
source ../scripts/env.sh
cd src-tauri
cargo check
cargo test                 # 53 passed / 3 ignored（含契约签名向量、凭据回退、审计查询参数编码；live 联调用例默认 ignored）
```

## 连接凭据

每台服务器在「设置」中可单独填写可选的 `AppKey` / `AppSecret`：

- 留空：使用客户端编译期内置的默认值；
- 填写：使用该服务器的自定义值，并保存在本地 `config.json`（权限 0600）；
- AppSecret 不回显、不返回前端；已保存时显示为掩码，保持不动保存会保留原值，点输入框清除按钮可恢复内置默认。

服务端启动时用 `--app-key` / `--app-secret`（或 `UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET`）设置的值必须与客户端一致；服务端未指定时同样使用内置默认值。

## 目录

```text
client/
├── src/                   React 页面（上传 / 文件 / 设置）与 invoke 封装
├── src-tauri/
│   ├── src/
│   │   ├── signer.rs      HMAC-SHA256 签名 + 单元测试（契约 2.3 测试向量）
│   │   ├── config.rs      配置读写（app_config_dir()/config.json，0600）
│   │   ├── api.rs         reqwest 签名请求、流式上传/下载、错误码映射
│   │   ├── commands.rs    Tauri 命令（契约 5.2）
│   │   └── lib.rs         Builder / 状态 / 命令注册
│   ├── capabilities/default.json   core:default + dialog:allow-open/save
│   └── tauri.conf.json    productName/mainBinaryName=ScopeFTP-client，窗口标题 ScopeFTP，identifier com.scopftp.client
└── ...
```

## 与契约的对应

- 命令：`get_config` / `save_config` / `verify_connection` / `activate` /
  `upload_file` / `list_files` / `download_file` / `delete_file`（契约 5.2）。
  另有一个只读辅助命令 `file_info`，仅用于上传前显示本地文件大小，不涉及密钥、不访问网络。
- 事件：`upload-progress`，payload `{uploadId, sent, total}`（契约 5.3）。
- 配置：`app_config_dir()/config.json`，权限 0600，`appSecret` 不返回给前端。
- 签名：严格按契约 2.3 的 8 行签名串；查询串按 2.4 规范化；payloadHash 按 2.5。
- 上传：先流式计算 SHA-256，再流式 multipart 上传并回报进度；`X-Content-Sha256` 参与签名。
- 下载：流式写入 + 边写边算 SHA-256，与响应头 `X-Content-Sha256` 不一致时删除文件并报错。
