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
  SafetyCertificateOutlined,
} from "@ant-design/icons";

import {
  activateDevice,
  deleteServer,
  errorMessage,
  saveServer,
  verifyConnection,
  verifyConnectionPreview,
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
  const { message } = AntdApp.useApp();
  const [form] = Form.useForm<SettingsFormValues>();
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dirty, setDirty] = useState(false);

  const activeServer = useMemo(
    () =>
      config?.servers.find((item) => item.id === config.activeServerId) ?? null,
    [config],
  );
  /** 验证连接（成功后会保存配置并激活设备）只能作用于当前服务器。 */
  const isActiveServer = !isNew && Boolean(server && server.id === activeServer?.id);
  /** 添加页可直接验证（成功后才添加）；编辑页只能验证当前服务器。 */
  const verifyDisabled = browserMode || (!isNew && !isActiveServer);
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
    // 添加页：不落盘，先用表单里的地址/凭据做「预览验证」；成功后再自动添加服务器。
    if (isNew) {
      let values: SettingsFormValues;
      try {
        values = await form.validateFields();
      } catch (error) {
        if (!isFormValidationError(error)) {
          message.error(errorMessage(error));
        }
        return;
      }
      setVerifying(true);
      try {
        await verifyConnectionPreview({
          serverUrl: values.serverUrl,
          appKey: values.appKey,
          appSecret:
            values.appSecret === SAVED_SECRET_MASK ? "" : values.appSecret,
        });
        const added = await doSave(true);
        if (!added) return;
        // 添加成功后自动激活设备：服务端开启设备绑定时，文件操作只认激活过的设备。
        // 激活失败不影响已添加的服务器（例如该 AppKey 已绑定其它设备），只提示原因。
        try {
          await activateDevice();
          message.success("连接验证成功，服务器已添加并激活设备");
        } catch (error) {
          message.warning(
            `服务器已添加，但设备激活失败：${errorMessage(error)}`,
          );
        }
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setVerifying(false);
      }
      return;
    }

    // 编辑页：有未保存修改时先保存，避免“填了但没保存”导致验证的是旧地址。
    if (computeDirty()) {
      const saved = await doSave(true);
      if (!saved) return;
    }
    setVerifying(true);
    try {
      const data = await verifyConnection();
      onVerified(data);
      // 服务端开启身份强制后，activate 前必须有近期 verify（否则按乱序拉黑），
      // 因此验证成功后自动激活设备；激活失败不影响验证结果，只提示原因。
      try {
        const activated = await activateDevice();
        message.success(`连接验证成功，设备已激活：${activated.deviceName}`);
        try {
          const refreshed = await verifyConnection();
          onVerified(refreshed);
        } catch {
          await onRecheck();
        }
      } catch (error) {
        message.warning(
          `连接验证成功，但设备激活失败：${errorMessage(error)}`,
        );
      }
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setVerifying(false);
    }
  }, [computeDirty, doSave, form, isNew, message, onRecheck, onVerified]);

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
      ? "不用先添加：验证成功后会自动添加服务器并激活设备"
      : isActiveServer
        ? (dirty ? "会先保存修改，再验证连接并激活设备" : "")
        : "只能验证当前服务器（验证成功才会保存修改），请先在左侧切换";
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
            tooltip="必填，必须与服务端启动时指定的 AppKey 一致"
            rules={[
              { required: true, whitespace: true, message: "请输入 AppKey" },
              { max: 128, message: "AppKey 最多 128 个字符" },
            ]}
          >
            <Input
              prefix={<KeyOutlined />}
              placeholder="与服务端启动时的 AppKey 一致"
              allowClear
              autoComplete="off"
              data-testid="server-app-key-input"
            />
          </Form.Item>
          <Form.Item
            label="AppSecret"
            name="appSecret"
            tooltip="必填，必须与服务端启动时指定的 AppSecret 一致"
            rules={[
              { required: true, whitespace: true, message: "请输入 AppSecret" },
              { max: 256, message: "AppSecret 最多 256 个字符" },
            ]}
          >
            <Input.Password
              prefix={<SafetyCertificateOutlined />}
              placeholder="与服务端启动时的 AppSecret 一致"
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
            <Tooltip title={verifyHint}>
              <span className="tooltip-button-wrap">
                <Button
                  type="primary"
                  icon={<ApiOutlined />}
                  loading={verifying || saving}
                  disabled={verifyDisabled}
                  onClick={() => void handleVerify()}
                  data-testid="verify-connection"
                >
                  验证连接
                </Button>
              </span>
            </Tooltip>
          </Space>
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
