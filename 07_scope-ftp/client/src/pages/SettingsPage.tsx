import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  App as AntdApp,
  Button,
  Card,
  Form,
  Input,
  Popconfirm,
  Space,
  Tooltip,
  Typography,
} from "antd";
import {
  ApiOutlined,
  DeleteOutlined,
  DesktopOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  GlobalOutlined,
  KeyOutlined,
  NodeIndexOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
} from "@ant-design/icons";

import {
  activateDevice,
  deleteServer,
  errorMessage,
  revealServerSecret,
  saveRelay,
  saveServer,
  verifyConnection,
  verifyConnectionPreview,
  verifyRelayPreview,
} from "../api";
import type {
  ConfigView,
  ConnectionState,
  ServerView,
  VerifyResult,
} from "../types";

const { Text } = Typography;

interface SettingsFormValues {
  name: string;
  serverUrl: string;
  deviceName: string;
  appKey: string;
  appSecret: string;
}

/**
 * 已保存的自定义 AppSecret 不在常规配置视图中回传，列表用固定掩码表示“保持原值”。
 * 用户点密码框右侧眼睛时才通过 Rust 命令读取并显示；点清除按钮后变成空串，保存时即清除。
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

/** 普通 HTTP（非本机）时提示生产建议 HTTPS。 */
function warnPlainHttp(url: string, message: { warning: (text: string) => void }) {
  const isPlainHttp = /^http:\/\//i.test(url);
  const isLocal =
    /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/.*)?$/i.test(url);
  if (isPlainHttp && !isLocal) {
    message.warning("生产环境建议使用 HTTPS，当前为明文 HTTP 连接");
  }
}

export interface SettingsPageProps {
  config: ConfigView | null;
  /** 当前编辑的服务器；`null` 表示新建（direct 或 relay）。 */
  server: ServerView | null;
  isNew: boolean;
  /** 新建代理服务器时的父服务器；其它情况为 null。 */
  relayParent: ServerView | null;
  connection: ConnectionState;
  browserMode: boolean;
  onSaved: (
    config: ConfigView,
    meta: { isNew: boolean },
  ) => void | Promise<void>;
  onDeleted: (config: ConfigView, deletedId: string) => void | Promise<void>;
  onVerified: (data: VerifyResult) => void;
  onRecheck: () => void | Promise<void>;
  /** 把“是否有未保存修改”同步给外层，用于离开设置页时提醒。 */
  onDirtyChange: (dirty: boolean) => void;
  /** 保存成功后打开该服务器的「设备与服务端」详情页。 */
  onOpenDetail: (id: string) => void;
}

export default function SettingsPage({
  config,
  server,
  isNew,
  relayParent,
  connection,
  browserMode,
  onSaved,
  onDeleted,
  onVerified,
  onRecheck,
  onDirtyChange,
  onOpenDetail,
}: SettingsPageProps) {
  const { message } = AntdApp.useApp();
  const [form] = Form.useForm<SettingsFormValues>();
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [secretVisible, setSecretVisible] = useState(false);
  /** 点击「查看」后从 Rust 读到的原值；仅用于 dirty 判断，避免查看动作被当成未保存修改。 */
  const revealedSecretRef = useRef<string | null>(null);

  const activeServer = useMemo(
    () =>
      config?.activeServer ??
      config?.servers.find((item) => item.id === config.activeServerId) ??
      null,
    [config],
  );

  /** 新建代理服务器（挂在 relayParent 下）。 */
  const isRelayNew = isNew && relayParent !== null;
  /** 当前表单是不是代理服务器（新建或编辑 relay）。 */
  const isRelay = isRelayNew || (!isNew && server?.kind === "relay");
  /** 验证连接（成功后会保存配置并激活设备）只能作用于当前服务器。 */
  const isActiveServer = !isNew && Boolean(server && server.id === activeServer?.id);
  /** 新建 relay 时父链必须是 active 且已连接（R1）。 */
  const relayParentReady = Boolean(
    relayParent &&
      relayParent.id === activeServer?.id &&
      connection.status === "connected",
  );
  const parentName = isRelayNew
    ? (relayParent?.name ?? "")
    : (server?.parentName ?? "");
  /** 新增/编辑代理服务器时，上方只展示它当前挂载的转发服务器（名称 + 地址）。 */
  const relaySource: ServerView | null = isRelayNew
    ? relayParent
    : isRelay && server?.parentId
      ? (config?.servers.find((item) => item.id === server.parentId) ?? null)
      : null;

  /** 上级链上已经使用的 AppKey。转发链每跳 AppKey 必须唯一，否则服务端会返回 40010。 */
  const relayParentKeys = useMemo(() => {
    if (!isRelay || !relaySource) return [];
    const all = config?.servers ?? [];
    return relaySource.chainHops
      .map((hop) =>
        all
          .find((item) => item.id === hop.id)
          ?.effectiveAppKey.trim()
          .toLowerCase(),
      )
      .filter((key): key is string => Boolean(key));
  }, [config?.servers, isRelay, relaySource]);

  const verifyDisabled =
    browserMode ||
    (isRelayNew ? !relayParentReady : !isNew && !isActiveServer);

  const initialValues = useMemo<SettingsFormValues>(
    () => ({
      name: server?.name ?? "",
      // 新建代理服务器时不要预填上级服务器的地址/凭据。
      serverUrl: relayParent
        ? ""
        : (server?.serverUrl ?? activeServer?.serverUrl ?? FALLBACK_SERVER_URL),
      deviceName: relayParent
        ? FALLBACK_DEVICE_NAME
        : (server?.deviceName ??
          activeServer?.deviceName ??
          FALLBACK_DEVICE_NAME),
      appKey: relayParent ? "" : (server?.appKey ?? ""),
      appSecret: relayParent
        ? ""
        : (server?.hasCustomSecret ? SAVED_SECRET_MASK : ""),
    }),
    [activeServer?.deviceName, activeServer?.serverUrl, relayParent, server],
  );

  /** 删除时会被级联删除的整棵子树数量。 */
  const subtreeCount = useMemo(() => {
    if (!server) return 0;
    const all = config?.servers ?? [];
    let count = 0;
    const walk = (id: string) => {
      for (const item of all) {
        if (item.parentId === id) {
          count += 1;
          walk(item.id);
        }
      }
    };
    walk(server.id);
    return count;
  }, [config?.servers, server]);

  const remainingAfterDelete =
    (config?.servers.length ?? 0) - 1 - subtreeCount;

  /** 表单当前值是否与已保存配置不同。 */
  const computeDirty = useCallback(() => {
    const values = form.getFieldsValue();
    const norm = (value?: string) => (value ?? "").trim();
    // 「查看」会把掩码替换成真实 secret，但这不是用户修改，不能触发离开提醒。
    const secretUnchanged =
      norm(values.appSecret) === norm(initialValues.appSecret) ||
      (initialValues.appSecret === SAVED_SECRET_MASK &&
        revealedSecretRef.current !== null &&
        norm(values.appSecret) === norm(revealedSecretRef.current));
    return (
      norm(values.name) !== norm(initialValues.name) ||
      norm(values.serverUrl) !== norm(initialValues.serverUrl) ||
      norm(values.deviceName) !== norm(initialValues.deviceName) ||
      norm(values.appKey) !== norm(initialValues.appKey) ||
      !secretUnchanged
    );
  }, [form, initialValues]);

  // 切换编辑对象时重置表单和 secret 查看状态。
  useEffect(() => {
    form.setFieldsValue(initialValues);
    revealedSecretRef.current = null;
    setSecretVisible(false);
    setDirty(false);
  }, [form, initialValues]);

  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  /** 保存表单；成功返回保存后的服务器 id，失败返回 null。 */
  const doSave = useCallback(
    async (): Promise<string | null> => {
      let values: SettingsFormValues;
      try {
        values = await form.validateFields();
      } catch (error) {
        if (!isFormValidationError(error)) {
          message.error(errorMessage(error));
        }
        return null;
      }

      setSaving(true);
      try {
        if (isRelay) {
          // 代理服务器（新建/编辑）：secret 留空/掩码表示保留。
          const secret =
            isRelayNew
              ? values.appSecret
              : !values.appSecret.trim() || values.appSecret === SAVED_SECRET_MASK
                ? null
                : values.appSecret;
          const saved = await saveRelay({
            id: isRelayNew ? null : (server?.id ?? null),
            parentId: isRelayNew
              ? (relayParent?.id ?? "")
              : (server?.parentId ?? ""),
            name: values.name,
            serverUrl: values.serverUrl,
            deviceName: values.deviceName,
            appKey: values.appKey,
            appSecret: secret,
          });
          setDirty(false);
          warnPlainHttp(values.serverUrl, message);
          await onSaved(saved, { isNew: isRelayNew });
          return server?.id ?? saved.activeServer?.id ?? null;
        }

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
        warnPlainHttp(values.serverUrl, message);
        await onSaved(saved, { isNew });
        return saved.activeServer?.id ?? saved.activeServerId ?? null;
      } catch (error) {
        message.error(errorMessage(error));
        return null;
      } finally {
        setSaving(false);
      }
    },
    [
      form,
      isNew,
      isRelay,
      isRelayNew,
      message,
      onSaved,
      relayParent?.id,
      server?.id,
      server?.parentId,
    ],
  );

  /** 点击密码框右侧眼睛：已保存服务器从本地配置读取原值并显示。 */
  const revealSavedSecret = useCallback(async () => {
    if (isNew || !server) return;
    const current = form.getFieldValue("appSecret") as string | undefined;
    if (current && current !== SAVED_SECRET_MASK) {
      setSecretVisible(true);
      return;
    }
    if (revealedSecretRef.current !== null) {
      setSecretVisible(true);
      return;
    }
    try {
      const secret = await revealServerSecret(server.id);
      revealedSecretRef.current = secret;
      form.setFieldValue("appSecret", secret);
      setSecretVisible(true);
    } catch (error) {
      message.error(errorMessage(error));
    }
  }, [form, isNew, message, server]);

  /** 点击输入框右侧的眼睛：未显示时读取原值并显示，已显示时隐藏。 */
  const handleSecretToggle = useCallback(() => {
    if (secretVisible) {
      setSecretVisible(false);
      // 明文只在前端保留到本次查看结束；隐藏时把表单值恢复成掩码，
      // 避免后续保存/离开页面时仍把 WebView 里的明文留在表单中。
      const current = form.getFieldValue("appSecret") as string | undefined;
      if (
        revealedSecretRef.current !== null &&
        current === revealedSecretRef.current
      ) {
        form.setFieldValue("appSecret", SAVED_SECRET_MASK);
        revealedSecretRef.current = null;
      }
      return;
    }
    if (isNew || !server) {
      setSecretVisible(true);
      return;
    }
    const current = form.getFieldValue("appSecret") as string | undefined;
    if (current && current !== SAVED_SECRET_MASK) {
      setSecretVisible(true);
      return;
    }
    void revealSavedSecret();
  }, [form, isNew, revealSavedSecret, secretVisible, server]);

  /** 转发链每跳 AppKey 必须不同；在发请求前拦住，返回更明确的提示。 */
  const checkRelayAppKeyUnique = useCallback(
    (appKey: string): boolean => {
      const normalized = appKey.trim().toLowerCase();
      if (!normalized || !relayParentKeys.includes(normalized)) {
        return true;
      }
      message.warning(
        "转发链中 AppKey 不能重复：每个中转服务器必须使用自己的 AppKey，请修改目标服务器的 AppKey",
      );
      return false;
    },
    [message, relayParentKeys],
  );

  /** 只保存本地配置，不验证连接；用于先落盘、稍后再修/验证。 */
  const handleSave = useCallback(async () => {
    const savedId = await doSave();
    if (!savedId) return;
    message.success(isNew ? "配置已保存" : "配置已更新");
  }, [doSave, isNew, message]);

  const handleVerify = useCallback(async () => {
    // 新建代理服务器：整链预览验证 → 保存 → 回到父服务器页面。
    if (isRelayNew) {
      if (!relayParent) {
        message.warning("上级服务器不存在，请重新选择");
        return;
      }
      if (!relayParentReady) {
        message.warning(
          `请先连接「${relayParent.name}」，连接成功后才能添加代理服务器`,
        );
        return;
      }
      let values: SettingsFormValues;
      try {
        values = await form.validateFields();
      } catch (error) {
        if (!isFormValidationError(error)) {
          message.error(errorMessage(error));
        }
        return;
      }
      if (!checkRelayAppKeyUnique(values.appKey)) {
        return;
      }
      setVerifying(true);
      try {
        const verifyData = await verifyRelayPreview({
          parentId: relayParent.id,
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
          parentId: relayParent.id,
          name: values.name,
          serverUrl: values.serverUrl,
          deviceName: values.deviceName,
          appKey: values.appKey,
          appSecret: values.appSecret,
        });
        setDirty(false);
        message.success(`代理服务器「${values.name}」已添加，当前服务器已切换`);
        warnPlainHttp(values.serverUrl, message);
        await onSaved(next, { isNew: true });
        const addedId =
          next.activeServer?.id ?? next.activeServerId ?? null;
        // 预览验证用的是临时 deviceId；保存后切换到了真实 deviceId，重新 verify 后
        // 立即激活目标设备，避免用户点进目录时因未激活被 40300 拦住。
        try {
          await activateDevice();
          message.success(`代理服务器「${values.name}」设备已激活`);
        } catch (error) {
          message.warning(
            `代理服务器已添加，但设备激活失败：${errorMessage(error)}`,
          );
        }
        // 保存成功后进入新代理服务器的详情页。
        if (addedId) onOpenDetail(addedId);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setVerifying(false);
      }
      return;
    }

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
        const addedId = await doSave();
        if (!addedId) return;
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
        // 保存成功后进入该服务器详情页。
        onOpenDetail(addedId);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setVerifying(false);
      }
      return;
    }

    // 编辑页：先用表单值做预览验证，验证成功后才保存；验证失败不落盘。
    let values: SettingsFormValues;
    try {
      values = await form.validateFields();
    } catch (error) {
      if (!isFormValidationError(error)) {
        message.error(errorMessage(error));
      }
      return;
    }
    if (isRelay && !checkRelayAppKeyUnique(values.appKey)) {
      return;
    }
    const dirtyNow = computeDirty();
    const detailId = server?.id ?? null;
    setVerifying(true);
    try {
      if (dirtyNow) {
        if (!server) return;
        const preview = isRelay
          ? await verifyRelayPreview({
              parentId: server.parentId ?? "",
              existingId: server.id,
              name: values.name,
              serverUrl: values.serverUrl,
              deviceName: values.deviceName,
              appKey: values.appKey,
              // 留空 / 掩码都表示沿用已保存的 secret。
              appSecret:
                values.appSecret === SAVED_SECRET_MASK
                  ? null
                  : values.appSecret || null,
            })
          : await verifyConnectionPreview({
              serverUrl: values.serverUrl,
              appKey: values.appKey,
              // null = 沿用已保存的 secret；空串 = 清除自定义密钥回退内置值。
              appSecret:
                values.appSecret === SAVED_SECRET_MASK ? null : values.appSecret,
              existingId: server.id,
            });
        // 验证成功后才保存（保存会更新配置并触发连接刷新）。
        const savedId = await doSave();
        if (!savedId) return;
        onVerified(preview);
      } else {
        const data = await verifyConnection();
        onVerified(data);
      }
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
      // 保存/验证成功后进入该服务器详情页，方便直接看到最新连接与绑定状态。
      if (detailId) onOpenDetail(detailId);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setVerifying(false);
    }
  }, [
    checkRelayAppKeyUnique,
    computeDirty,
    doSave,
    form,
    isNew,
    isRelay,
    isRelayNew,
    message,
    onOpenDetail,
    onRecheck,
    onVerified,
    relayParent,
    relayParentReady,
    server,
  ]);

  const handleDelete = useCallback(async () => {
    if (!server) return;
    setDeleting(true);
    try {
      const next = await deleteServer(server.id);
      message.success(`已删除「${server.name}」`);
      await onDeleted(next, server.id);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setDeleting(false);
    }
  }, [message, onDeleted, server]);

  const deleteDescription = (() => {
    if (!server) return "";
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

  const verifyHint = browserMode
    ? "浏览器预览环境不可用"
    : isRelayNew
      ? relayParentReady
        ? "可直接保存配置；「验证连接」成功后会保存并切换为当前服务器"
        : `请先连接「${relayParent?.name ?? "上级服务器"}」再验证；也可直接保存本地配置`
      : isNew
        ? "可直接保存配置；「验证连接」成功后会添加服务器并激活设备"
        : isActiveServer
          ? dirty
            ? "可直接保存配置；「验证连接」成功后会保存并激活设备"
            : ""
          : "可直接保存本地配置；「验证连接」只能验证当前服务器";

  const relaySecretRules =
    isRelay && !isRelayNew
      ? [{ max: 256, message: "AppSecret 最多 256 个字符" }]
      : [
          { required: true, whitespace: true, message: "请输入 AppSecret" },
          { max: 256, message: "AppSecret 最多 256 个字符" },
        ];

  return (
    <div className="settings-grid" data-testid="settings-grid">
      <Card
        className="page-card settings-card"
        title={
          <Space>
            {isRelay ? (
              <NodeIndexOutlined style={{ color: "#1677ff" }} />
            ) : (
              <ApiOutlined style={{ color: "#1677ff" }} />
            )}
            {isRelayNew
              ? "添加代理服务器"
              : isNew
                ? "添加服务器"
                : isRelay
                  ? "代理服务器配置"
                  : "服务器配置"}
          </Space>
        }
        extra={
          !isNew && server ? (
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
                data-testid="delete-server"
                disabled={browserMode}
              >
                {server.kind === "relay" ? "删除代理服务器" : "删除服务器"}
              </Button>
            </Popconfirm>
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {isRelayNew ? "挂在上级服务器下的代理服务器" : "保存后自动切换为当前服务器"}
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
          {isRelay && (
            <div className="settings-relay-source">
              <div className="settings-relay-source-label">当前转发服务器</div>
              <div
                className="settings-relay-source-value"
                data-testid="settings-relay-source"
              >
                <Text
                  strong
                  className="settings-relay-source-name"
                  ellipsis
                >
                  {relaySource?.name || "-"}
                </Text>
                <Text className="settings-relay-source-address" ellipsis>
                  {relaySource?.serverUrl || "-"}
                </Text>
              </div>
            </div>
          )}
          <Form.Item
            label={isRelay ? "代理服务器名称" : "服务器名称"}
            name="name"
            rules={[
              { required: true, message: "请输入服务器名称" },
              { max: 64, message: "服务器名称最多 64 个字符" },
            ]}
          >
            <Input
              prefix={<DesktopOutlined />}
              placeholder={isRelay ? "代理服务器名称" : "开发环境"}
              allowClear
              autoComplete="off"
              data-testid="server-name-input"
            />
          </Form.Item>
          <Form.Item
            label={
              isRelay
                ? `目标中转服务器地址（${parentName || "上级服务器"} 可访问）`
                : "服务器地址"
            }
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
              placeholder={
                isRelay ? "http://10.0.0.5:8080" : "http://127.0.0.1:8080"
              }
              allowClear
              autoComplete="off"
              data-testid="server-url-input"
            />
          </Form.Item>
          <Form.Item
            label={isRelay ? "目标服务器的 AppKey" : "AppKey"}
            name="appKey"
            tooltip={
              isRelay
                ? "目标中转服务器自己的 AppKey"
                : "必填，必须与服务端启动时指定的 AppKey 一致"
            }
            rules={[
              { required: true, whitespace: true, message: "请输入 AppKey" },
              { max: 128, message: "AppKey 最多 128 个字符" },
            ]}
          >
            <Input
              prefix={<KeyOutlined />}
              placeholder={
                isRelay
                  ? "目标服务器的 AppKey"
                  : "与服务端启动时的 AppKey 一致"
              }
              allowClear
              autoComplete="off"
              data-testid="server-app-key-input"
            />
          </Form.Item>
          <Form.Item
            label={isRelay ? "目标服务器的 AppSecret" : "AppSecret"}
            name="appSecret"
            tooltip={
              isRelay
                ? "目标中转服务器自己的 AppSecret；编辑时留空表示保留原值"
                : "必填，必须与服务端启动时指定的 AppSecret 一致"
            }
            rules={relaySecretRules}
          >
            <Input
              type={secretVisible ? "text" : "password"}
              prefix={<SafetyCertificateOutlined />}
              suffix={
                <Button
                  type="text"
                  size="small"
                  className="settings-secret-eye"
                  icon={
                    secretVisible ? (
                      <EyeOutlined />
                    ) : (
                      <EyeInvisibleOutlined />
                    )
                  }
                  aria-label={secretVisible ? "隐藏 AppSecret" : "查看 AppSecret"}
                  title={secretVisible ? "隐藏 AppSecret" : "查看 AppSecret"}
                  disabled={browserMode}
                  onClick={handleSecretToggle}
                  data-testid="toggle-app-secret"
                />
              }
              placeholder={
                isRelay
                  ? isRelayNew
                    ? "目标服务器的 AppSecret"
                    : "留空表示保留原值"
                  : "与服务端启动时的 AppSecret 一致"
              }
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
            label={isRelay ? "设备名称（目标服务器）" : "设备名称"}
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
              icon={<SaveOutlined />}
              loading={saving}
              disabled={browserMode || verifying}
              onClick={() => void handleSave()}
              data-testid="save-config"
            >
              保存配置
            </Button>
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
    </div>
  );
}
