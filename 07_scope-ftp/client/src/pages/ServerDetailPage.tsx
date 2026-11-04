import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Popconfirm,
  Space,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import {
  DeleteOutlined,
  DesktopOutlined,
  EditOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  NodeIndexOutlined,
} from "@ant-design/icons";

import { getLocalNetworkInfo } from "../api";
import type {
  ConnectionState,
  LocalNetworkInfo,
  ServerView,
  StorageRoot,
  VerifyResult,
} from "../types";
import { formatBytes, formatDateTime } from "../utils";

const { Text } = Typography;

export interface ServerDetailPageProps {
  /** 要展示的服务器（不一定是当前服务器）。 */
  server: ServerView;
  /** 是否为当前服务器：只有当前服务器才有真实的连接/绑定/存储目录数据。 */
  isActiveServer: boolean;
  connection: ConnectionState;
  /** 本地全部服务器（用于计算删除时会级联的子树数量）。 */
  servers: ServerView[];
  /** 当前服务器的验证结果；非当前服务器时为 null。 */
  verify: VerifyResult | null;
  /** 当前服务器的存储根；非当前服务器时为空。 */
  roots: StorageRoot[];
  browserMode: boolean;
  /** 打开编辑页。 */
  onEdit: (id: string) => void;
  /** 浏览文件：切到该服务器并回到目录浏览。 */
  onBrowseFiles: (id: string) => void;
  /** 点击转发链 hop：切到该服务器并回到目录浏览。 */
  onSelectServer: (id: string) => void;
  /** 重新检测当前服务器连接。 */
  onRecheck: () => void | Promise<void>;
  /** 在当前服务器下新建代理服务器。 */
  onAddRelay: (parentId: string) => void;
  /** 删除服务器（服务端会级联删除其下所有代理服务器）。 */
  onDelete: (id: string) => Promise<void>;
}

/**
 * 「设备与服务端」详情页（只读）：从左侧服务器列表的详情图标进入。
 *
 * 不切换当前服务器：非当前服务器只能看静态配置，连接状态、设备绑定、
 * 存储目录等需要连接才能拿到的信息提示切换为当前服务器后再查看。
 */
export default function ServerDetailPage({
  server,
  isActiveServer,
  connection,
  servers,
  verify,
  roots,
  browserMode,
  onEdit,
  onBrowseFiles,
  onSelectServer,
  onRecheck,
  onAddRelay,
  onDelete,
}: ServerDetailPageProps) {
  const inactiveHint = "切换为当前服务器后可查看";
  const [deleting, setDeleting] = useState(false);

  /** 删除时会被级联删除的整棵子树数量。 */
  const subtreeCount = useMemo(() => {
    let count = 0;
    const walk = (id: string) => {
      for (const item of servers) {
        if (item.parentId === id) {
          count += 1;
          walk(item.id);
        }
      }
    };
    walk(server.id);
    return count;
  }, [server.id, servers]);

  const remainingAfterDelete = servers.length - 1 - subtreeCount;

  const deleteDescription = (() => {
    const parts: string[] = [];
    if (subtreeCount > 0) {
      parts.push(
        `会同时删除其下 ${subtreeCount} 个代理服务器的本地配置和续传状态，不会删除服务器上的文件。`,
      );
    } else {
      parts.push("会删除该服务器的本地配置与未完成的续传状态，不会删除服务端文件。");
    }
    if (remainingAfterDelete <= 0) {
      parts.push("这是最后一台服务器，删除后进入空配置。");
    }
    return parts.join("");
  })();

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await onDelete(server.id);
    } catch {
      // 父组件已经弹出错误提示；这里只负责恢复按钮状态。
    } finally {
      setDeleting(false);
    }
  };

  /** 本机 MAC / 局域网 IP：进入页面时取一次，取不到显示「未获取到」。 */
  const [localNet, setLocalNet] = useState<LocalNetworkInfo | null>(null);
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const info = await getLocalNetworkInfo();
        if (alive) setLocalNet(info);
      } catch {
        // 浏览器预览或系统限制取不到时不打扰用户，按「未获取到」展示。
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const browseDisabled = browserMode || !server.configured;
  const browseDisabledReason = browserMode
    ? "浏览器预览环境不可用"
    : !server.configured
      ? "该服务器还没有填写服务器地址"
      : "";

  /** 添加代理服务器：父服务器必须是当前服务器且已连接。 */
  const canAddRelay =
    !browserMode && isActiveServer && connection.status === "connected";
  const addRelayDisabledReason = browserMode
    ? "浏览器预览环境不可用"
    : !isActiveServer
      ? `请先在左侧切换到「${server.name}」再添加代理服务器`
      : connection.status !== "connected"
        ? `请先连接「${server.name}」，连接成功后才能添加代理服务器`
        : "";

  return (
    <div className="server-detail-grid" data-testid="server-detail-page">
      <Card
        className="page-card settings-card settings-info-card"
        title={
          <Space>
            <DesktopOutlined style={{ color: "#1677ff" }} />
            设备与服务端
            {!isActiveServer && (
              <Tag className="server-detail-inactive-tag">
                非当前服务器
              </Tag>
            )}
          </Space>
        }
        extra={
          <Space wrap>
            <Tooltip title={browseDisabledReason || "打开该服务器的目录浏览"}>
              <span className="tooltip-button-wrap">
                <Button
                  size="small"
                  icon={<FolderOpenOutlined />}
                  disabled={browseDisabled}
                  onClick={() => onBrowseFiles(server.id)}
                  data-testid="server-detail-browse"
                >
                  浏览文件
                </Button>
              </span>
            </Tooltip>
            <Tooltip title={browserMode ? "浏览器预览环境不可用" : "编辑该服务器"}>
              <span className="tooltip-button-wrap">
                <Button
                  size="small"
                  type="primary"
                  icon={<EditOutlined />}
                  disabled={browserMode}
                  onClick={() => onEdit(server.id)}
                  data-testid="server-detail-edit"
                >
                  编辑
                </Button>
              </span>
            </Tooltip>
            <Tooltip title={addRelayDisabledReason || "在当前服务器下添加代理服务器"}>
              <span className="tooltip-button-wrap">
                <Button
                  size="small"
                  icon={<NodeIndexOutlined />}
                  disabled={!canAddRelay}
                  onClick={() => onAddRelay(server.id)}
                  data-testid="add-relay-button"
                >
                  代理服务器
                </Button>
              </span>
            </Tooltip>
            <Tooltip title={browserMode ? "浏览器预览环境不可用" : "删除该服务器"}>
              <span className="tooltip-button-wrap">
                <Popconfirm
                  title={
                    server.kind === "relay"
                      ? `删除代理服务器「${server.name}」？`
                      : `删除服务器「${server.name}」？`
                  }
                  description={deleteDescription}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => void handleDelete()}
                >
                  <Button
                    size="small"
                    danger
                    type="text"
                    icon={<DeleteOutlined />}
                    loading={deleting}
                    disabled={browserMode || deleting}
                    data-testid="server-detail-delete"
                  >
                    {server.kind === "relay" ? "删除代理服务器" : "删除服务器"}
                  </Button>
                </Popconfirm>
              </span>
            </Tooltip>
          </Space>
        }
        data-testid="server-detail-card"
      >
        {!isActiveServer && (
          <Alert
            className="server-detail-note"
            type="info"
            showIcon
            message={`「${server.name}」不是当前服务器`}
            description="连接状态、设备绑定、服务端标识和存储目录需要切换为当前服务器后才能查看；点「浏览文件」会自动切换。"
          />
        )}

        <Descriptions
          column={1}
          size="small"
          colon={false}
          className="app-descriptions settings-descriptions"
        >
          <Descriptions.Item label="服务器">
            <Text ellipsis className="ellipsis-text value-wrap">
              {server.name}
            </Text>
          </Descriptions.Item>
          {server.kind === "relay" && (
            <Descriptions.Item label="上级服务器">
              <Text ellipsis className="ellipsis-text value-wrap">
                {server.parentName || "-"}
              </Text>
            </Descriptions.Item>
          )}
          {server.kind === "relay" && (
            <Descriptions.Item label="转发链">
              {server.chainHops.length > 0 ? (
                <Space
                  wrap
                  size={0}
                  className="settings-chain"
                  data-testid="server-detail-chain"
                >
                  {server.chainHops.map((hop, index) => (
                    <span key={hop.id} className="settings-chain-hop-wrap">
                      {index > 0 && (
                        <Text type="secondary" className="settings-chain-arrow">
                          {" → "}
                        </Text>
                      )}
                      <Button
                        type="link"
                        size="small"
                        className="settings-chain-hop"
                        onClick={() => onSelectServer(hop.id)}
                        data-testid="server-detail-chain-hop"
                      >
                        {hop.name}
                      </Button>
                    </span>
                  ))}
                </Space>
              ) : (
                <Text type="secondary">
                  {server.chainPath.join(" → ") || "-"}
                </Text>
              )}
            </Descriptions.Item>
          )}
          <Descriptions.Item label="服务器地址">
            <Tooltip title={server.serverUrl}>
              <Text className="mono-text ellipsis-text value-wrap">
                {server.serverUrl || "-"}
              </Text>
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label="设备 ID">
            {server.deviceId ? (
              <Text
                className="mono-text wrap-text"
                copyable={{ text: server.deviceId }}
              >
                {server.deviceId}
              </Text>
            ) : (
              <Text type="secondary">首次启动后自动生成</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="设备名称">
            <Text ellipsis className="ellipsis-text value-wrap">
              {server.deviceName || "-"}
            </Text>
          </Descriptions.Item>
          <Descriptions.Item label="本机 MAC">
            {localNet?.mac ? (
              <Text
                className="mono-text wrap-text"
                copyable={{ text: localNet.mac }}
              >
                {localNet.mac}
              </Text>
            ) : (
              <Text type="secondary">未获取到</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="本机 IP">
            {localNet?.ip ? (
              <Text
                className="mono-text wrap-text"
                copyable={{ text: localNet.ip }}
              >
                {localNet.ip}
              </Text>
            ) : (
              <Text type="secondary">未获取到</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="AppKey">
            <Text code className="mono-text wrap-text">
              {server.effectiveAppKey || "-"}
            </Text>
            {server.hasCustomSecret && (
              <Tag color="blue" style={{ marginLeft: 8 }}>
                自定义密钥
              </Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="连接状态">
            {isActiveServer ? (
              <>
                <Tag
                  color={
                    connection.status === "connected"
                      ? "green"
                      : connection.status === "failed"
                        ? "red"
                        : "default"
                  }
                >
                  {connection.status === "connected"
                    ? "已连接"
                    : connection.status === "failed"
                      ? "连接失败"
                      : connection.status === "checking"
                        ? "检测中"
                        : "未配置"}
                </Tag>
                {connection.message && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {connection.message}
                  </Text>
                )}
              </>
            ) : (
              <>
                <Tag>未连接（非当前服务器）</Tag>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  切换为当前服务器后自动验证
                </Text>
              </>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="绑定状态">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : verify ? (
              verify.device ? (
                <Tag color="green">
                  已绑定：{verify.device.deviceName}（
                  {formatDateTime(verify.device.boundAt)}）
                </Tag>
              ) : (
                <Tag>未绑定设备</Tag>
              )
            ) : (
              <Text type="secondary">验证连接后显示</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="设备绑定">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : verify ? (
              verify.deviceBindingEnabled ? (
                <Tag color="orange">服务端已开启</Tag>
              ) : (
                <Tag>服务端未开启</Tag>
              )
            ) : (
              <Text type="secondary">-</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="服务端标识">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : verify ? (
              <Space direction="vertical" size={0}>
                <Text>{verify.name || "-"}</Text>
                <Text type="secondary" className="mono-text wrap-text">
                  {verify.appKey}
                </Text>
              </Space>
            ) : (
              <Text type="secondary">-</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="存储目录">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : roots.length > 0 ? (
              <div className="settings-roots" data-testid="server-detail-roots">
                {roots.map((root) => (
                  <div
                    key={root.id}
                    className="settings-root-item"
                    data-testid="server-detail-root-item"
                    data-root-id={root.id}
                  >
                    <FolderOutlined className="settings-root-icon" />
                    <div className="settings-root-main">
                      <Text strong className="settings-root-name">
                        {root.name || root.id}
                      </Text>
                      <Text
                        type="secondary"
                        className="mono-text wrap-text settings-root-path"
                        title={root.path}
                      >
                        {root.path}
                      </Text>
                    </div>
                    <Text type="secondary" className="mono-text settings-root-id">
                      {root.id}
                    </Text>
                  </div>
                ))}
              </div>
            ) : verify?.storageDir ? (
              <Text
                className="mono-text wrap-text"
                copyable={{ text: verify.storageDir }}
              >
                {verify.storageDir}
              </Text>
            ) : (
              <Text type="secondary">
                {verify ? "旧版服务端：单存储根" : "验证连接后显示"}
              </Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="单文件上限">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : verify ? (
              formatBytes(verify.limits.maxFileSize)
            ) : (
              "-"
            )}
          </Descriptions.Item>
          <Descriptions.Item label="服务器时间">
            {!isActiveServer ? (
              <Text type="secondary">{inactiveHint}</Text>
            ) : verify ? (
              formatDateTime(new Date(verify.serverTime).toISOString())
            ) : (
              "-"
            )}
          </Descriptions.Item>
        </Descriptions>

        {isActiveServer && connection.status === "failed" && (
          <Alert
            style={{ marginTop: 16 }}
            type="warning"
            showIcon
            message="连接检测未通过"
            description={connection.message}
            action={
              <Button size="small" onClick={() => void onRecheck()}>
                重新检测
              </Button>
            }
          />
        )}
      </Card>
    </div>
  );
}
