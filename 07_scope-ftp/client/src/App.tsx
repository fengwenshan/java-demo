import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, App as AntdApp, Button, Empty, Layout, Spin, Typography } from "antd";

import {
  errorMessage,
  getConfig,
  isTauri,
  listStorageRoots,
  setActiveServer,
  verifyConnection,
} from "./api";
import type {
  ConfigView,
  ConnectionState,
  ServerView,
  StorageRoot,
  VerifyResult,
} from "./types";
import ServerSidebar from "./components/ServerSidebar";
import ServerBrowser from "./pages/ServerBrowser";
import SettingsPage from "./pages/SettingsPage";

const { Content } = Layout;
const { Text } = Typography;

type ViewKey = "browser" | "settings";

/** 设置页当前编辑的对象：新建 / 编辑某台服务器 / 在父服务器下新建转发目标。 */
type SettingsTarget =
  | { kind: "new" }
  | { kind: "edit"; id: string }
  | { kind: "newRelay"; parentId: string }
  | null;

export default function App() {
  const { message, modal } = AntdApp.useApp();
  /** 右侧内容：目录浏览（默认）或设置。 */
  const [view, setView] = useState<ViewKey>("browser");
  const [settingsTarget, setSettingsTarget] = useState<SettingsTarget>(null);
  /** 设置页有未保存修改时，离开前拦截并提示（避免“填完没保存”的误解）。 */
  const [settingsDirty, setSettingsDirty] = useState(false);
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [loadingConfig, setLoadingConfig] = useState(true);
  const [browserMode, setBrowserMode] = useState(!isTauri());
  const [connection, setConnection] = useState<ConnectionState>({
    status: "checking",
  });
  const [verify, setVerify] = useState<VerifyResult | null>(null);
  /** 当前服务器的存储根列表（task-16）；旧单根服务端为空，rootId 保持 null。 */
  const [roots, setRoots] = useState<StorageRoot[]>([]);
  const [activeRootId, setActiveRootId] = useState<string | null>(null);
  /** 每台服务器记住上次选择的存储根（仅内存，重启后回到 defaultRootId）。 */
  const rootByServerRef = useRef<Record<string, string>>({});

  const activeServer: ServerView | null =
    config?.activeServer ??
    config?.servers.find((server) => server.id === config.activeServerId) ??
    null;
  const configured = Boolean(
    activeServer?.configured && config?.appKey && config?.hasSecret,
  );

  const activeRoot: StorageRoot | null =
    roots.find((root) => root.id === activeRootId) ?? roots[0] ?? null;

  const editingServer: ServerView | null =
    settingsTarget?.kind === "edit"
      ? (config?.servers.find((server) => server.id === settingsTarget.id) ??
        null)
      : null;

  /** 新建转发目标时的父服务器（A/B/C）。 */
  const relayParent: ServerView | null =
    settingsTarget?.kind === "newRelay"
      ? (config?.servers.find(
          (server) => server.id === settingsTarget.parentId,
        ) ?? null)
      : null;

  /** 拉取当前服务器的存储根；旧服务端没有该接口时静默回退单根模式。 */
  const refreshRoots = useCallback(async (serverId: string | null) => {
    if (!serverId) {
      setRoots([]);
      setActiveRootId(null);
      return;
    }
    try {
      const data = await listStorageRoots();
      setRoots(data.roots);
      const remembered = rootByServerRef.current[serverId];
      const preferred =
        data.roots.find((root) => root.id === remembered)?.id ??
        (data.defaultRootId || data.roots[0]?.id || null);
      setActiveRootId(preferred);
      if (preferred) rootByServerRef.current[serverId] = preferred;
    } catch {
      // 兼容旧单根服务端：没有 /api/storage/roots，保持「不传 root」的默认根行为。
      setRoots([]);
      setActiveRootId(null);
    }
  }, []);

  const refreshConnection = useCallback(async (next: ConfigView) => {
    if (!isTauri()) {
      setBrowserMode(true);
      setConnection({ status: "failed", message: "请使用桌面客户端运行" });
      return;
    }
    const server =
      next.activeServer ??
      next.servers.find((item) => item.id === next.activeServerId) ??
      null;
    if (!server) {
      // 空配置：还没有服务器；右侧显示空态并引导添加。
      setVerify(null);
      setRoots([]);
      setActiveRootId(null);
      setConnection({
        status: "unconfigured",
        message: "还没有服务器，请先添加",
      });
      return;
    }
    if (!server.configured || !next.appKey || !next.hasSecret) {
      setVerify(null);
      setRoots([]);
      setActiveRootId(null);
      setConnection({ status: "unconfigured", message: "尚未完成配置" });
      return;
    }
    setConnection({ status: "checking" });
    try {
      const data = await verifyConnection();
      setVerify(data);
      setConnection({
        status: "connected",
        message: `已连接：${data.name || next.appKey}`,
      });
      await refreshRoots(server.id);
    } catch (error) {
      const text = errorMessage(error);
      setVerify(null);
      setRoots([]);
      setActiveRootId(null);
      setConnection({ status: "failed", message: text });
      message.error(text);
    }
  }, [message, refreshRoots]);

  useEffect(() => {
    let cancelled = false;

    if (!isTauri()) {
      setLoadingConfig(false);
      setConnection({ status: "failed", message: "请使用桌面客户端运行" });
      return;
    }

    (async () => {
      try {
        const next = await getConfig();
        if (cancelled) return;
        setConfig(next);
        setLoadingConfig(false);
        await refreshConnection(next);
      } catch (error) {
        if (cancelled) return;
        setBrowserMode(true);
        setLoadingConfig(false);
        setConnection({ status: "failed", message: errorMessage(error) });
        message.error(errorMessage(error));
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [message, refreshConnection]);

  /** 设置页有未保存修改时，先确认再离开。 */
  const withLeaveGuard = useCallback(
    (action: () => void) => {
      if (view !== "settings" || !settingsDirty) {
        action();
        return;
      }
      modal.confirm({
        title: "设置尚未保存",
        content: "当前设置页有未保存的修改，离开后将丢失。",
        okText: "放弃修改并离开",
        cancelText: "留在设置页",
        okButtonProps: { danger: true },
        onOk: () => {
          setSettingsDirty(false);
          action();
        },
      });
    },
    [modal, settingsDirty, view],
  );

  /** 切换当前服务器：更新配置 + 重新验证连接；右侧目录浏览器整体重载。 */
  const handleSelectServer = useCallback(
    (id: string) => {
      if (!config) return;
      const switchTo = () => {
        if (id === config.activeServerId) {
          setSettingsTarget(null);
          setView("browser");
          return;
        }
        void (async () => {
          try {
            const next = await setActiveServer(id);
            setConfig(next);
            setSettingsTarget(null);
            setVerify(null);
            setRoots([]);
            setActiveRootId(null);
            setView("browser");
            await refreshConnection(next);
          } catch (error) {
            message.error(errorMessage(error));
          }
        })();
      };
      withLeaveGuard(switchTo);
    },
    [config, message, refreshConnection, withLeaveGuard],
  );

  /** 切换存储根：右侧目录整体重载（path 重置为空），并记住该服务器的选择。 */
  const handleSelectRoot = useCallback(
    (id: string) => {
      if (!id || id === activeRootId) return;
      withLeaveGuard(() => {
        if (activeServer) rootByServerRef.current[activeServer.id] = id;
        setActiveRootId(id);
        setView("browser");
      });
    },
    [activeRootId, activeServer, withLeaveGuard],
  );

  const handleAddServer = useCallback(() => {
    withLeaveGuard(() => {
      setSettingsTarget({ kind: "new" });
      setView("settings");
    });
  }, [withLeaveGuard]);

  /** 在父服务器下新建转发目标（父必须存在；连接校验在设置页内完成）。 */
  const handleAddRelay = useCallback(
    (parentId: string) => {
      if (!config?.servers.some((server) => server.id === parentId)) {
        message.warning("上级服务器不存在，请刷新后重试");
        return;
      }
      withLeaveGuard(() => {
        setSettingsTarget({ kind: "newRelay", parentId });
        setView("settings");
      });
    },
    [config?.servers, message, withLeaveGuard],
  );

  const handleEditServer = useCallback(
    (id: string) => {
      withLeaveGuard(() => {
        setSettingsTarget({ kind: "edit", id });
        setView("settings");
      });
    },
    [withLeaveGuard],
  );

  const handleOpenSettings = useCallback(() => {
    withLeaveGuard(() => {
      setSettingsTarget(
        activeServer ? { kind: "edit", id: activeServer.id } : { kind: "new" },
      );
      setView("settings");
    });
  }, [activeServer, withLeaveGuard]);

  /** 保存服务器配置成功后：更新列表；新建时进入该服务器的编辑态（保持停留）。 */
  const handleConfigSaved = useCallback(
    async (next: ConfigView, meta: { isNew: boolean }) => {
      setConfig(next);
      if (meta.isNew) {
        // 以后端返回的 activeServer 为准（空配置时可能为 null），不要自己兜底。
        const focus =
          next.activeServer ??
          next.servers.find((server) => server.id === next.activeServerId) ??
          null;
        setSettingsTarget(focus ? { kind: "edit", id: focus.id } : null);
      }
      await refreshConnection(next);
    },
    [refreshConnection],
  );

  const handleServerDeleted = useCallback(
    async (next: ConfigView, _deletedId: string) => {
      setConfig(next);
      setSettingsTarget(null);
      setSettingsDirty(false);
      setView("browser");
      setVerify(null);
      await refreshConnection(next);
    },
    [refreshConnection],
  );

  const handleVerified = useCallback(
    (data: VerifyResult) => {
      setVerify(data);
      setConnection({
        status: "connected",
        message: `已连接：${data.name || "服务器"}`,
      });
      // 手动验证/激活后同步存储根（首次挂载时验证失败的情况下也能恢复多根列表）。
      if (activeServer) void refreshRoots(activeServer.id);
    },
    [activeServer, refreshRoots],
  );

  const handleRecheck = useCallback(async () => {
    if (!isTauri()) return;
    try {
      const next = config ?? (await getConfig());
      if (!config) setConfig(next);
      await refreshConnection(next);
    } catch (error) {
      const text = errorMessage(error);
      setConnection({ status: "failed", message: text });
      message.error(text);
    }
  }, [config, message, refreshConnection]);

  return (
    <Layout className="app-shell">
      <Layout.Sider width={288} theme="light" className="app-sider">
        <ServerSidebar
          config={config}
          activeServer={activeServer}
          connection={connection}
          configured={configured}
          roots={roots}
          activeRootId={activeRoot?.id ?? null}
          onSelectRoot={handleSelectRoot}
          onSelectServer={handleSelectServer}
          onAddServer={handleAddServer}
          onAddRelay={handleAddRelay}
          onEditServer={handleEditServer}
          onOpenSettings={handleOpenSettings}
        />
      </Layout.Sider>

      <Layout className="app-main-layout">
        <Content className="app-main-content">
          {browserMode && (
            <Alert
              className="browser-hint"
              data-testid="browser-hint"
              type="warning"
              showIcon
              message="请使用桌面客户端运行"
            />
          )}

          {loadingConfig ? (
            <div className="app-loading">
              <Spin size="large" />
              <Text type="secondary">正在加载配置…</Text>
            </div>
          ) : view === "browser" ? (
            // 空配置（没有任何服务器 / 没有 active）时右侧显示空态；有 active 才渲染浏览器。
            config && !config.activeServer ? (
              <div className="empty-config" data-testid="empty-config">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="还没有服务器"
                >
                  <Button type="primary" onClick={handleAddServer}>
                    添加服务器
                  </Button>
                </Empty>
              </div>
            ) : (
              <ServerBrowser
                key={`${activeServer?.id ?? "unconfigured"}:${activeRoot?.id ?? "default"}`}
                configured={configured}
                serverName={activeServer?.name}
                chainPath={activeServer?.chainPath}
                servers={config?.servers}
                onSelectServer={handleSelectServer}
                rootId={activeRoot?.id ?? null}
                rootName={activeRoot?.name ?? null}
                rootPath={activeRoot?.path ?? null}
                roots={roots}
                onGoSettings={handleOpenSettings}
              />
            )
          ) : (
            <>
              <SettingsPage
                config={config}
                server={editingServer}
                isNew={
                  settingsTarget?.kind === "new" ||
                  settingsTarget?.kind === "newRelay"
                }
                relayParent={relayParent}
                verify={verify}
                connection={connection}
                browserMode={browserMode}
                roots={roots}
                onSaved={handleConfigSaved}
                onDeleted={handleServerDeleted}
                onVerified={handleVerified}
                onRecheck={handleRecheck}
                onDirtyChange={setSettingsDirty}
                onAddRelay={handleAddRelay}
                onSelectServer={handleSelectServer}
              />
            </>
          )}
        </Content>

      </Layout>
    </Layout>
  );
}
