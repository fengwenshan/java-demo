import type { ReactNode } from "react";
import {
  DesktopOutlined,
  FolderOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import { Alert, Button, Tooltip, Typography } from "antd";

import type {
  ConfigView,
  ConnectionState,
  ServerView,
  StorageRoot,
} from "../types";

const { Text } = Typography;

export interface ServerSidebarProps {
  config: ConfigView | null;
  activeServer: ServerView | null;
  connection: ConnectionState;
  /** 同组服务器的连通性（active 自身使用 connection）。 */
  serverHealth: Record<string, ConnectionState>;
  configured: boolean;
  /** 当前服务器的存储根（task-16；旧单根服务端为空）。 */
  roots: StorageRoot[];
  activeRootId: string | null;
  onSelectRoot: (id: string) => void;
  onSelectServer: (id: string) => void;
  onAddServer: () => void;
  /** 打开该服务器的「设备与服务端」详情页（只读，不切换当前服务器）。 */
  onOpenDetail: (id: string) => void;
  onOpenSettings: () => void;
}

/** 左侧固定栏：多服务器列表（切换/查看详情）+ 存储目录。 */
export default function ServerSidebar({
  config,
  activeServer,
  connection,
  serverHealth,
  configured,
  roots,
  activeRootId,
  onSelectRoot,
  onSelectServer,
  onAddServer,
  onOpenDetail,
  onOpenSettings,
}: ServerSidebarProps) {
  const activeRoot =
    roots.find((root) => root.id === activeRootId) ?? roots[0] ?? null;
  const servers = config?.servers ?? [];

  /** 按 parentId 分组：递归渲染 A → B → C 的转发树。 */
  const childrenOf = (parentId: string | null) =>
    servers.filter((server) => (server.parentId ?? null) === parentId);

  const renderServer = (server: ServerView, depth: number): ReactNode => {
    const active = server.id === activeServer?.id;
    const health = serverHealth[server.id];
    // active 同时参考 connection 与同组 ping 结果：任何一方连通即显示绿色；
    // ping 进行中优先显示黄色，避免主连接成功把重新检测的状态盖掉。
    const status = active
      ? health?.status === "checking"
        ? health
        : health?.status === "connected"
          ? health
          : connection.status === "connected"
            ? connection
            : (health ?? connection)
      : health;
    const connected = status?.status === "connected";
    const checking = status?.status === "checking";
    const statusTitle = connected
      ? "已连接"
      : checking
        ? "检测中"
        : status?.message || "未连接";
    const children = childrenOf(server.id);
    return (
      <div key={server.id} className="server-tree-node">
        <div
          role="button"
          tabIndex={0}
          className={`server-list-item${active ? " server-list-item-active" : ""}${
            server.kind === "relay" ? " server-list-item-relay" : ""
          }`}
          style={{ paddingLeft: 8 + depth * 16 }}
          data-testid="server-list-item"
          data-server-id={server.id}
          data-server-kind={server.kind}
          data-parent-id={server.parentId ?? ""}
          data-active={active ? "true" : "false"}
          title={`${server.name} · ${server.serverUrl}`}
          onClick={() => onSelectServer(server.id)}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              onSelectServer(server.id);
            }
          }}
        >
          <span
            className={`server-status-dot${connected ? " is-active" : ""}${
              checking ? " is-checking" : ""
            }`}
            title={statusTitle}
            data-testid="server-status-dot"
            data-status={status?.status ?? "unknown"}
          />
          <div className="server-list-main">
            <Text strong={active} ellipsis className="server-list-name">
              {server.name}
            </Text>
            <Text type="secondary" ellipsis className="server-list-url">
              {server.serverUrl}
            </Text>
          </div>
          <Tooltip title={`查看「${server.name}」设备与服务端详情`}>
            <Button
              type="text"
              size="small"
              className="server-list-detail"
              icon={<DesktopOutlined />}
              aria-label={`查看 ${server.name} 详情`}
              data-testid="server-detail"
              onClick={(event) => {
                event.stopPropagation();
                onOpenDetail(server.id);
              }}
            />
          </Tooltip>
        </div>
        {children.map((child) => renderServer(child, depth + 1))}
      </div>
    );
  };

  return (
    <div className="server-sidebar">
      {/* ---------- 多服务器列表 ---------- */}
      <section className="server-section" data-testid="server-section">
        <div className="server-section-header">
          <Text type="secondary" className="server-section-title">
            服务器（{servers.length}）
          </Text>
          <Tooltip title="添加服务器">
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              aria-label="添加服务器"
              data-testid="add-server"
              onClick={onAddServer}
            />
          </Tooltip>
        </div>
        <div className="server-list" data-testid="server-list">
          {childrenOf(null).map((server) => renderServer(server, 0))}
          {servers.length === 0 && (
            <Text type="secondary" className="server-empty-hint">
              还没有服务器，点击右上角 + 添加。
            </Text>
          )}
        </div>
      </section>

      {/* ---------- 存储目录（task-16 多存储根；只有一个根时隐藏） ---------- */}
      {roots.length > 1 && (
        <section className="root-section" data-testid="root-section">
          <div className="server-section-header">
            <Text type="secondary" className="server-section-title">
              存储目录（{roots.length}）
            </Text>
          </div>
          <div className="root-list" data-testid="root-list">
            {roots.map((root) => {
              const active = root.id === activeRoot?.id;
              return (
                <Tooltip
                  key={root.id}
                  title={`${root.name} · ${root.path}`}
                  placement="right"
                >
                  <div
                    role="button"
                    tabIndex={0}
                    className={`root-list-item${active ? " root-list-item-active" : ""}`}
                    data-testid="root-list-item"
                    data-root-id={root.id}
                    data-active={active ? "true" : "false"}
                    onClick={() => onSelectRoot(root.id)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        onSelectRoot(root.id);
                      }
                    }}
                  >
                    <FolderOutlined
                      className={`root-list-icon${active ? " is-active" : ""}`}
                    />
                    <div className="root-list-main">
                      <Text
                        strong={active}
                        ellipsis
                        className="root-list-name"
                      >
                        {root.name || root.id}
                      </Text>
                      <Text
                        type="secondary"
                        ellipsis
                        className="root-list-path"
                      >
                        {root.path}
                      </Text>
                    </div>
                  </div>
                </Tooltip>
              );
            })}
          </div>
        </section>
      )}

      {!configured && (
        <Alert
          type="info"
          showIcon
          message="尚未完成配置"
          action={
            <Button size="small" type="primary" onClick={onOpenSettings}>
              去设置
            </Button>
          }
        />
      )}

    </div>
  );
}
