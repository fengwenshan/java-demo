import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  App as AntdApp,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Popconfirm,
  Space,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import {
  ApiOutlined,
  DeleteOutlined,
  DesktopOutlined,
  FolderOutlined,
  GlobalOutlined,
  KeyOutlined,
  SaveOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";

import {
  activateDevice,
  deleteServer,
  errorMessage,
  saveServer,
  verifyConnection,
} from "../api";
import type {
  ConfigView,
  ConnectionState,
  ServerView,
  StorageRoot,
  VerifyResult,
} from "../types";
import { formatBytes, formatDateTime } from "../utils";

const { Text } = Typography;

interface SettingsFormValues {
  name: string;
  serverUrl: string;
  deviceName: string;
  appKey: string;
  appSecret: string;
}

/**
 * 已保存的自定义 AppSecret 不回传前端，用固定掩码表示“保持原值”。
 * 用户点输入框的清除按钮后变成空串，保存时即清除自定义 secret；
 * 保持掩码不动时保存为 null，后端保留原值。
 */
const SAVED_SECRET_MASK = "********";

const FALLBACK_SERVER_URL = "http://127.0.0.1:8080";
const FALLBACK_DEVICE_NAME = "我的设备";

function isFormValidationError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "errorFields" in (error as Record<string, unknown>)
  );
}

export interface SettingsPageProps {
  config: ConfigView | null;
  /** 当前编辑的服务器；`null` 表示新建。 */
  server: ServerView | null;
  isNew: boolean;
  verify: VerifyResult | null;
  connection: ConnectionState;
  browserMode: boolean;
  /** 当前服务器的存储根（task-16，只读展示）。 */
  roots: StorageRoot[];
  /** 当前服务器数量 > 1 时才允许删除。 */
  canDelete: boolean;
  onSaved: (
    config: ConfigView,
    meta: { isNew: boolean },
  ) => void | Promise<void>;
  onDeleted: (config: ConfigView, deletedId: string) => void | Promise<void>;
  onVerified: (data: VerifyResult) => void;
  onRecheck: () => void | Promise<void>;
  /** 把“是否有未保存修改”同步给外层，用于离开设置页时提醒。 */
  onDirtyChange: (dirty: boolean) => void;
}

export default function SettingsPage({
  config,
  server,
  isNew,
  verify,
  connection,
  browserMode,
  roots,
  canDelete,
  onSaved,
  onDeleted,
  onVerified,
  onRecheck,
  onDirtyChange,
}: SettingsPageProps) {
  const { message, modal } = AntdApp.useApp();
  const [form] = Form.useForm<SettingsFormValues>();
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [activating, setActivating] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dirty, setDirty] = useState(false);

  const activeServer = useMemo(
    () =>
      config?.servers.find((item) => item.id === config.activeServerId) ?? null,
    [config],
  );
  /** 验证连接 / 激活设备只能作用于当前服务器。 */
  const isActiveServer = !isNew && Boolean(server && server.id === activeServer?.id);
  const verifyDisabled = browserMode || !isActiveServer;

  const initialValues = useMemo<SettingsFormValues>(
    () => ({
      name: server?.name ?? "",
      serverUrl: server?.serverUrl ?? activeServer?.serverUrl ?? FALLBACK_SERVER_URL,
      deviceName:
        server?.deviceName ?? activeServer?.deviceName ?? FALLBACK_DEVICE_NAME,
      appKey: server?.appKey ?? "",
      appSecret: server?.hasCustomSecret ? SAVED_SECRET_MASK : "",
    }),
    [activeServer?.deviceName, activeServer?.serverUrl, server],
  );

  /** 表单当前值是否与已保存配置不同。 */
  const computeDirty = useCallback(() => {
    const values = form.getFieldsValue();
    const norm = (value?: string) => (value ?? "").trim();
    return (
      norm(values.name) !== norm(initialValues.name) ||
      norm(values.serverUrl) !== norm(initialValues.serverUrl) ||
      norm(values.deviceName) !== norm(initialValues.deviceName) ||
      norm(values.appKey) !== norm(initialValues.appKey) ||
      norm(values.appSecret) !== norm(initialValues.appSecret)
    );
  }, [form, initialValues]);

  // 切换编辑对象时重置表单。
  useEffect(() => {
    form.setFieldsValue(initialValues);
    setDirty(false);
  }, [form, initialValues]);

  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  const doSave = useCallback(
    async (silent = false): Promise<boolean> => {
      let values: SettingsFormValues;
      try {
        values = await form.validateFields();
      } catch (error) {
        if (!isFormValidationError(error)) {
          message.error(errorMessage(error));
        }
        return false;
      }

      setSaving(true);
      try {
        const saved = await saveServer({
          id: isNew ? null : (server?.id ?? null),
          name: values.name,
          serverUrl: values.serverUrl,
          deviceName: values.deviceName,
          appKey: values.appKey,
          appSecret:
            values.appSecret === SAVED_SECRET_MASK ? null : values.appSecret,
        });
        setDirty(false);
        if (!silent) {
          message.success(isNew ? "服务器已添加并切换为当前服务器" : "服务器配置已保存");
        }
        await onSaved(saved, { isNew });

        const isPlainHttp = /^http:\/\//i.test(saved.servers.find((item) => item.id === (isNew ? saved.activeServerId : server?.id))?.serverUrl ?? "");
        const isLocal =
          /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/.*)?$/i.test(
            saved.servers.find((item) => item.id === (isNew ? saved.activeServerId : server?.id))?.serverUrl ?? "",
          );
        if (isPlainHttp && !isLocal) {
          message.warning("生产环境建议使用 HTTPS，当前为明文 HTTP 连接");
        }
        return true;
      } catch (error) {
        message.error(errorMessage(error));
        return false;
      } finally {
        setSaving(false);
      }
    },
    [form, isNew, message, onSaved, server?.id],
  );

  const handleVerify = useCallback(async () => {
    // 有未保存修改时先保存，避免“填了但没保存”导致验证的是旧地址。
    if (computeDirty()) {
      const saved = await doSave(true);
      if (!saved) return;
    }
    setVerifying(true);
    try {
      const data = await verifyConnection();
      onVerified(data);
      message.success("连接验证成功");
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setVerifying(false);
    }
  }, [computeDirty, doSave, message, onVerified]);

  const handleActivate = useCallback(async () => {
    if (computeDirty()) {
      const saved = await doSave(true);
      if (!saved) return;
    }
    const deviceName =
      (form.getFieldValue("deviceName") as string | undefined) ??
      server?.deviceName ??
      "";
    modal.confirm({
      title: "激活当前设备",
      content: `将使用设备「${deviceName || "未命名设备"}」与当前 AppKey 绑定。若服务端已绑定其它设备且开启设备绑定，激活会失败。`,
      okText: "激活",
      cancelText: "取消",
      onOk: async () => {
        setActivating(true);
        try {
          const data = await activateDevice();
          message.success(`设备激活成功：${data.deviceName}`);
          try {
            const verified = await verifyConnection();
            onVerified(verified);
          } catch {
            await onRecheck();
          }
        } catch (error) {
          message.error(errorMessage(error));
        } finally {
          setActivating(false);
        }
      },
    });
  }, [
    computeDirty,
    doSave,
    form,
    message,
    modal,
    onRecheck,
    onVerified,
    server?.deviceName,
  ]);

  const handleDelete = useCallback(async () => {
    if (!server) return;
    setDeleting(true);
    try {
      const next = await deleteServer(server.id);
      message.success(`已删除服务器「${server.name}」`);
      await onDeleted(next, server.id);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setDeleting(false);
    }
  }, [message, onDeleted, server]);

  const verifyHint = browserMode
    ? "浏览器预览环境不可用"
    : isNew
      ? "请先保存服务器后再验证连接"
      : isActiveServer
        ? ""
        : "只能验证当前服务器，请先在左侧点击切换";

  const saveDisabled = browserMode;

  return (
    <div className="settings-grid" data-testid="settings-grid">
      <Card
        className="page-card settings-card"
        title={
          <Space>
            <ApiOutlined style={{ color: "#1677ff" }} />
            {isNew ? "添加服务器" : "服务器配置"}
          </Space>
        }
        extra={
          !isNew && canDelete ? (
            <Popconfirm
              title="删除该服务器？"
              description="会删除该服务器的本地配置与未完成的续传状态，不会删除服务端文件。"
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
                data-testid="delete-server"
                disabled={browserMode}
              >
                删除服务器
              </Button>
            </Popconfirm>
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>
              至少保留 1 台服务器
            </Text>
          )
        }
      >
        <Form
          form={form}
          layout="vertical"
          requiredMark="optional"
          initialValues={initialValues}
          disabled={browserMode}
          onValuesChange={() => setDirty(computeDirty())}
          onFinish={() => void doSave()}
          data-testid="settings-form"
        >
          <Form.Item
            label="服务器名称"
            name="name"
            rules={[
              { required: true, message: "请输入服务器名称" },
              { max: 64, message: "服务器名称最多 64 个字符" },
            ]}
          >
            <Input
              prefix={<DesktopOutlined />}
              placeholder="开发环境"
              allowClear
              autoComplete="off"
              data-testid="server-name-input"
            />
          </Form.Item>
          <Form.Item
            label="服务器地址"
            name="serverUrl"
            extra="支持 Nginx 子路径转发，例如 https://example.com/scopeftp"
            rules={[
              { required: true, message: "请输入服务器地址" },
              {
                pattern: /^https?:\/\/\S+$/i,
                message: "地址需以 http:// 或 https:// 开头",
              },
            ]}
          >
            <Input
              prefix={<GlobalOutlined />}
              placeholder="http://127.0.0.1:8080"
              allowClear
              autoComplete="off"
              data-testid="server-url-input"
            />
          </Form.Item>
          <Form.Item
            label="AppKey"
            name="appKey"
            tooltip="留空使用客户端内置 AppKey；必须与服务端启动时的 AppKey 一致"
            rules={[{ max: 128, message: "AppKey 最多 128 个字符" }]}
          >
            <Input
              prefix={<KeyOutlined />}
              placeholder={server?.effectiveAppKey ?? config?.appKey ?? "留空使用内置默认"}
              allowClear
              autoComplete="off"
              data-testid="server-app-key-input"
            />
          </Form.Item>
          <Form.Item
            label="AppSecret"
            name="appSecret"
            tooltip="留空使用内置 AppSecret；点输入框的清除按钮可恢复内置默认"
            rules={[{ max: 256, message: "AppSecret 最多 256 个字符" }]}
          >
            <Input.Password
              prefix={<SafetyCertificateOutlined />}
              placeholder="留空使用内置默认"
              allowClear
              autoComplete="new-password"
              onFocus={(event) => {
                if (event.currentTarget.value === SAVED_SECRET_MASK) {
                  event.currentTarget.select();
                }
              }}
              data-testid="server-app-secret-input"
            />
          </Form.Item>
          <Form.Item
            label="设备名称"
            name="deviceName"
            rules={[
              { required: true, message: "请输入设备名称" },
              { max: 64, message: "设备名称最多 64 个字符" },
            ]}
          >
            <Input
              prefix={<DesktopOutlined />}
              placeholder="我的 MacBook"
              allowClear
              data-testid="device-name-input"
            />
          </Form.Item>
          <Space wrap>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={saveDisabled}
              data-testid="save-server"
            >
              {isNew ? "添加服务器" : "保存"}
            </Button>
            <Tooltip title={verifyHint}>
              <span className="tooltip-button-wrap">
                <Button
                  icon={<ApiOutlined />}
                  loading={verifying}
                  disabled={verifyDisabled}
                  onClick={() => void handleVerify()}
                  data-testid="verify-connection"
                >
                  验证连接
                </Button>
              </span>
            </Tooltip>
            <Tooltip title={verifyHint}>
              <span className="tooltip-button-wrap">
                <Button
                  icon={<SafetyCertificateOutlined />}
                  loading={activating}
                  disabled={verifyDisabled}
                  onClick={handleActivate}
                >
                  激活设备
                </Button>
              </span>
            </Tooltip>
          </Space>
          <Text type="secondary" style={{ display: "block", marginTop: 12, fontSize: 12 }}>
            每台服务器拥有独立的 deviceId / deviceName；AppKey / AppSecret 可留空使用内置默认，也可按服务器单独覆盖。AppSecret 已保存时显示为掩码，点输入框右侧清除按钮可恢复内置默认。切换服务器后，验证连接、激活设备与所有文件操作都作用于当前服务器。
          </Text>
        </Form>
      </Card>

      <Card
        className="page-card settings-card settings-info-card"
        title={
          <Space>
            <DesktopOutlined style={{ color: "#1677ff" }} />
            设备与服务端
          </Space>
        }
        data-testid="settings-info-card"
      >
        <Descriptions
          column={1}
          size="small"
          colon={false}
          className="app-descriptions settings-descriptions"
        >
          <Descriptions.Item label="服务器">
            <Text ellipsis className="ellipsis-text value-wrap">
              {server?.name ?? "保存后显示"}
            </Text>
          </Descriptions.Item>
          <Descriptions.Item label="服务器地址">
            <Tooltip title={server?.serverUrl}>
              <Text className="mono-text ellipsis-text value-wrap">
                {server?.serverUrl ?? "-"}
              </Text>
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label="设备 ID">
            {server?.deviceId ? (
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
              {server?.deviceName ?? "-"}
            </Text>
          </Descriptions.Item>
          <Descriptions.Item label="AppKey">
            <Text code className="mono-text wrap-text">
              {server?.effectiveAppKey ?? config?.appKey ?? "-"}
            </Text>
            {server?.hasCustomSecret && (
              <Tag color="blue" style={{ marginLeft: 8 }}>
                自定义密钥
              </Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="连接状态">
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
          </Descriptions.Item>
          <Descriptions.Item label="绑定状态">
            {verify ? (
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
            {verify ? (
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
            {verify ? (
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
            {roots.length > 0 ? (
              <div className="settings-roots" data-testid="settings-roots">
                {roots.map((root) => (
                  <div
                    key={root.id}
                    className="settings-root-item"
                    data-testid="settings-root-item"
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
                {!isActiveServer && (
                  <Text type="secondary" className="settings-root-hint">
                    以上为当前连接服务器的存储目录；切换到该服务器后可浏览其内容。
                  </Text>
                )}
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
            {verify ? formatBytes(verify.limits.maxFileSize) : "-"}
          </Descriptions.Item>
          <Descriptions.Item label="服务器时间">
            {verify
              ? formatDateTime(new Date(verify.serverTime).toISOString())
              : "-"}
          </Descriptions.Item>
        </Descriptions>


        {connection.status === "failed" && (
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
