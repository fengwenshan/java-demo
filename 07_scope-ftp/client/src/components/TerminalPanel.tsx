import { useCallback, useEffect, useRef, useState } from "react";
import {
  App as AntdApp,
  Button,
  Input,
  Spin,
  Tooltip,
  Typography,
} from "antd";
import type { InputRef } from "antd";
import {
  ClearOutlined,
  CloseOutlined,
  CodeOutlined,
} from "@ant-design/icons";

import { errorMessage, listServerDir, terminalExec } from "../api";

const { Text } = Typography;

export interface TerminalPanelProps {
  /** 面板是否展开；关闭只隐藏 DOM，组件状态（历史/会话）保留。 */
  open: boolean;
  /** 当前服务器名称（header 展示）。 */
  serverName?: string;
  /** 当前存储根 id，透传给 terminal_exec 的 root。 */
  rootId: string | null;
  /** 当前存储根绝对路径；Tab 补全时用于把 cwd 换算成相对路径。 */
  rootPath?: string | null;
  /** 本次打开终端希望定位到的绝对 cwd（右键文件/目录、工具栏当前目录）。 */
  initialCwd?: string | null;
  /** 打开/定位令牌：变化时新建 session、重置 cwd/清屏（保留命令历史）。 */
  openToken?: number;
  onClose: () => void;
}

type TerminalLineKind =
  | "command"
  | "output"
  | "error"
  | "warning"
  | "blocked"
  | "system";

interface TerminalLine {
  id: number;
  kind: TerminalLineKind;
  text: string;
}

/** 去除 ANSI 颜色/光标控制序列，终端输出按纯文本渲染（task-30）。 */
export function stripAnsi(text: string): string {
  // eslint-disable-next-line no-control-regex
  return text.replace(
    // CSI（颜色/光标）、OSC（标题/超链接）等常见转义序列。
    // eslint-disable-next-line no-control-regex
    /[\u001B\u009B][[\]()#;?]*(?:(?:(?:[a-zA-Z\d]*(?:;[-a-zA-Z\d/#&.:=?%@~_]+)*)?\u0007)|(?:(?:\d{1,4}(?:[;:]\d{0,4})*)?[\dA-PR-TZcf-nq-uy=><~]))/g,
    "",
  );
}

/** 生成 8-64 位 `[A-Za-z0-9_-]` 的终端 sessionId；优先 crypto.randomUUID。 */
export function newTerminalSessionId(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof cryptoApi?.getRandomValues === "function") {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** 输出按行拆分，去掉末尾换行产生的空行。 */
function splitOutputLines(output: string): string[] {
  const normalized = output.replace(/\r\n/g, "\n");
  if (!normalized) {
    return [];
  }
  const lines = normalized.split("\n");
  if (lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/** 服务端拦截类型：高危命令 / 非安全目录（task-30/task-32）。 */
export type BlockedKind = "high-risk" | "unsafe-path";

const HIGH_RISK_PREFIX = "高危命令已禁止执行";
const UNSAFE_PATH_PREFIXES = ["命令涉及非安全目录", "目录不在安全范围"];

/**
 * 识别服务端 40000 拦截类型（message 可能带 Rust 统一映射的「请求参数错误：」前缀）。
 *
 * - 「高危命令已禁止执行」：命令被 TerminalGuard 硬拦；
 * - 「命令涉及非安全目录」/「目录不在安全范围」：命令路径或 cd 目标超出
 *   `app.storage.roots`（task-32）。
 */
export function detectBlockedMessage(message: string): BlockedKind | null {
  if (message.includes(HIGH_RISK_PREFIX)) {
    return "high-risk";
  }
  if (UNSAFE_PATH_PREFIXES.some((prefix) => message.includes(prefix))) {
    return "unsafe-path";
  }
  return null;
}

/** 是否为服务端拦截（高危命令或终端越界）。 */
export function isBlockedMessage(message: string): boolean {
  return detectBlockedMessage(message) !== null;
}

/** 统一从 「请求参数错误：…」外壳中取出服务端 message 原文。 */
function stripApiPrefix(message: string): string {
  const candidates = [HIGH_RISK_PREFIX, ...UNSAFE_PATH_PREFIXES];
  const index = candidates
    .map((prefix) => message.indexOf(prefix))
    .filter((position) => position >= 0)
    .sort((left, right) => left - right)[0];
  return index === undefined ? message : message.slice(index);
}

/** 归一化相对路径；`..` 越出根时返回 null（Tab 补全不越界）。 */
function normalizeRelativePath(path: string): string | null {
  const stack: string[] = [];
  for (const part of path.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") {
      if (stack.length === 0) return null;
      stack.pop();
      continue;
    }
    stack.push(part);
  }
  return stack.join("/");
}

/** 最长公共前缀（Tab 多候选时补到公共前缀）。 */
function commonPrefix(names: string[]): string {
  if (names.length === 0) return "";
  let prefix = names[0];
  for (const name of names.slice(1)) {
    let index = 0;
    while (index < prefix.length && index < name.length && prefix[index] === name[index]) {
      index += 1;
    }
    prefix = prefix.slice(0, index);
    if (!prefix) break;
  }
  return prefix;
}

/**
 * 底部终端面板（task-30）：VS Code 风格深色面板。
 *
 * - 命令通过 `terminal_exec` 在服务器执行，sessionId 前端生成、服务端维护 cwd；
 * - 输出行支持 stdout/stderr/非零退出/超时/截断/高危拦截等状态；
 * - ↑/↓ 历史（最多 100 条），清空只清输出，关闭面板保留历史；
 * - 高危命令：输出区红色拦截行 + Modal.warning（命令 + 服务端原因）。
 */
export default function TerminalPanel({
  open,
  serverName,
  rootId,
  rootPath,
  initialCwd,
  openToken,
  onClose,
}: TerminalPanelProps) {
  const { message, modal } = AntdApp.useApp();
  const [lines, setLines] = useState<TerminalLine[]>([]);
  const [input, setInput] = useState("");
  const [running, setRunning] = useState(false);
  const [cwd, setCwd] = useState<string | null>(null);
  const [history, setHistory] = useState<string[]>([]);

  const sessionIdRef = useRef(newTerminalSessionId());
  const lineSeqRef = useRef(0);
  /** 新 session 的首条命令需要带上 requestedCwd，之后交给服务端会话 cwd。 */
  const pendingCwdRef = useRef<string | null>(null);
  const lastOpenTokenRef = useRef<number | null>(null);
  const completionBusyRef = useRef(false);
  const historyIndexRef = useRef(-1);
  const draftRef = useRef("");
  const inputRef = useRef<InputRef>(null);
  const outputRef = useRef<HTMLDivElement>(null);

  const pushLines = useCallback(
    (items: Array<{ kind: TerminalLineKind; text: string }>) => {
      if (items.length === 0) {
        return;
      }
      setLines((previous) => [
        ...previous,
        ...items.map((item) => {
          lineSeqRef.current += 1;
          return { id: lineSeqRef.current, ...item };
        }),
      ]);
    },
    [],
  );

  useEffect(() => {
    if (!open) {
      return;
    }
    const output = outputRef.current;
    if (output) {
      output.scrollTop = output.scrollHeight;
    }
  }, [input, lines, open, openToken, running]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(timer);
  }, [open, openToken]);

  /**
   * 打开/定位终端（task-34）：token 变化时新建 sessionId、重置 cwd 到
   * initialCwd、清空输出并加一行系统提示；命令历史保留。
   * 仅关闭再打开（token 不变）不会重置，仍保留输出与历史。
   */
  useEffect(() => {
    if (!open || openToken === undefined) {
      return;
    }
    if (lastOpenTokenRef.current === openToken) {
      return;
    }
    lastOpenTokenRef.current = openToken;
    sessionIdRef.current = newTerminalSessionId();
    pendingCwdRef.current = initialCwd ?? null;
    setCwd(initialCwd ?? null);
    setLines([]);
    lineSeqRef.current = 0;
    historyIndexRef.current = -1;
    draftRef.current = "";
    setInput("");
    pushLines([
      {
        kind: "system",
        text: `终端工作目录：${initialCwd ?? "当前目录"}`,
      },
    ]);
  }, [initialCwd, open, openToken, pushLines]);

  /** 把输入框光标处 token 替换为补全结果，并把光标放到补全末尾。 */
  const replaceCompletionToken = useCallback(
    (completion: string, cursor: number, tokenLength: number) => {
      const nextValue =
        input.slice(0, cursor - tokenLength) + completion + input.slice(cursor);
      setInput(nextValue);
      window.setTimeout(() => {
        const element = inputRef.current?.input;
        if (element) {
          const caret = cursor - tokenLength + completion.length;
          element.selectionStart = caret;
          element.selectionEnd = caret;
          element.focus();
        }
      }, 0);
    },
    [input],
  );

  /** Tab 路径补全（task-35）：用 list_server_dir 列当前目录/子目录条目。 */
  const completePath = useCallback(async () => {
    if (running || completionBusyRef.current) return;
    const element = inputRef.current?.input;
    const cursor = element?.selectionStart ?? input.length;
    const beforeCursor = input.slice(0, cursor);
    const tokenMatch = /(?:^|[\s;|&])([^\s;|&]*)$/.exec(beforeCursor);
    const token = tokenMatch?.[1] ?? "";
    // 选项/变量/引号不做补全（简单安全策略）。
    if (token.startsWith("-") || /[$~"'`\\]/.test(token) || token.includes("..")) {
      return;
    }
    const slashIndex = token.lastIndexOf("/");
    const dirPart = slashIndex >= 0 ? token.slice(0, slashIndex + 1) : "";
    const prefix = slashIndex >= 0 ? token.slice(slashIndex + 1) : token;

    const rootBase = (rootPath ?? "").replace(/\/+$/, "");
    const currentDir = cwd ?? initialCwd ?? null;
    if (!rootBase || !currentDir) {
      return;
    }
    const insideRoot =
      currentDir === rootBase || currentDir.startsWith(rootBase + "/");
    if (!insideRoot) {
      pushLines([
        { kind: "system", text: "当前目录不在当前根，暂不支持补全" },
      ]);
      return;
    }

    let targetRelative: string;
    if (dirPart.startsWith("/")) {
      const absoluteDir = dirPart.replace(/\/+$/, "");
      if (
        !(absoluteDir === rootBase || absoluteDir.startsWith(rootBase + "/"))
      ) {
        return;
      }
      targetRelative =
        absoluteDir === rootBase ? "" : absoluteDir.slice(rootBase.length + 1);
    } else {
      const cwdRelative =
        currentDir === rootBase ? "" : currentDir.slice(rootBase.length + 1);
      const combined = `${cwdRelative}/${dirPart}`.replace(/^\/+/, "");
      const normalized = normalizeRelativePath(combined);
      if (normalized === null) {
        return;
      }
      targetRelative = normalized;
    }

    completionBusyRef.current = true;
    try {
      const listing = await listServerDir(targetRelative, rootId);
      const candidates = (listing.entries ?? []).filter((entry) =>
        entry.name.startsWith(prefix),
      );
      if (candidates.length === 0) {
        return;
      }
      const labels = candidates.map((entry) =>
        entry.type === "directory" ? `${entry.name}/` : `${entry.name} `,
      );
      if (candidates.length === 1) {
        replaceCompletionToken(dirPart + labels[0], cursor, token.length);
        return;
      }
      pushLines([{ kind: "output", text: labels.join("   ") }]);
      const shared = commonPrefix(candidates.map((entry) => entry.name));
      if (shared.length > prefix.length) {
        replaceCompletionToken(dirPart + shared, cursor, token.length);
      }
    } catch {
      // 补全失败静默，不打断终端命令执行。
    } finally {
      completionBusyRef.current = false;
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [
    cwd,
    initialCwd,
    input,
    pushLines,
    replaceCompletionToken,
    rootId,
    rootPath,
    running,
  ]);

  const runCommand = useCallback(async () => {
    const command = input.trim();
    if (!command || running) {
      return;
    }
    setInput("");
    setHistory((previous) => {
      const next =
        previous[previous.length - 1] === command
          ? previous
          : [...previous, command];
      return next.slice(-100);
    });
    historyIndexRef.current = -1;
    draftRef.current = "";
    pushLines([{ kind: "command", text: `$ ${command}` }]);
    setRunning(true);
    try {
      const requestedCwd = pendingCwdRef.current ?? cwd;
      const result = await terminalExec(
        sessionIdRef.current,
        command,
        requestedCwd,
        rootId,
      );
      pendingCwdRef.current = null;
      setCwd(result.cwd);
      const outputLines = splitOutputLines(stripAnsi(result.output));
      if (outputLines.length > 0) {
        pushLines(outputLines.map((text) => ({ kind: "output" as const, text })));
      }
      if (result.exitCode < 0) {
        pushLines([
          {
            kind: "error",
            text: `✖ 执行超时或异常终止（exit ${result.exitCode}）`,
          },
        ]);
      } else if (result.exitCode !== 0) {
        pushLines([{ kind: "error", text: `[exit ${result.exitCode}]` }]);
      }
      if (result.truncated) {
        pushLines([
          { kind: "warning", text: "⚠ 输出超过服务端上限，已截断显示" },
        ]);
      }
    } catch (error) {
      const messageText = errorMessage(error);
      const blockedKind = detectBlockedMessage(messageText);
      if (blockedKind) {
        // 去掉 Rust 统一错误映射加上的「请求参数错误：」前缀，保留服务端 message 原文。
        const blockedMessage = stripApiPrefix(messageText);
        const title =
          blockedKind === "high-risk" ? "高危命令已禁止" : "命令涉及非安全目录";
        pushLines([{ kind: "blocked", text: `⛔ 已拦截：${blockedMessage}` }]);
        modal.warning({
          title,
          content: (
            <div className="terminal-blocked-dialog" data-testid="terminal-blocked-dialog">
              <p>
                命令：<code>{command}</code>
              </p>
              <p>{blockedMessage}</p>
            </div>
          ),
          okText: "我知道了",
        });
      } else {
        message.error(messageText || "命令执行失败");
        pushLines([
          { kind: "error", text: `✖ ${messageText || "命令执行失败"}` },
        ]);
      }
    } finally {
      setRunning(false);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [cwd, input, message, modal, pushLines, rootId, running]);

  const navigateHistory = useCallback(
    (direction: -1 | 1) => {
      if (history.length === 0) {
        return;
      }
      if (direction === -1) {
        if (historyIndexRef.current === -1) {
          draftRef.current = input;
          historyIndexRef.current = history.length - 1;
        } else if (historyIndexRef.current > 0) {
          historyIndexRef.current -= 1;
        }
        setInput(history[historyIndexRef.current]);
      } else {
        if (historyIndexRef.current === -1) {
          return;
        }
        if (historyIndexRef.current < history.length - 1) {
          historyIndexRef.current += 1;
          setInput(history[historyIndexRef.current]);
        } else {
          historyIndexRef.current = -1;
          setInput(draftRef.current);
        }
      }
      window.setTimeout(() => inputRef.current?.focus(), 0);
    },
    [history, input],
  );

  const clearLines = useCallback(() => {
    setLines([]);
    window.setTimeout(() => inputRef.current?.focus(), 0);
  }, []);

  if (!open) {
    return null;
  }

  return (
    <section className="terminal-panel" data-testid="terminal-panel">
      <header className="terminal-header">
        <span className="terminal-title">
          <CodeOutlined /> 终端
        </span>
        <span className="terminal-server" data-testid="terminal-server">
          {serverName ?? "未配置服务器"}
        </span>
        <span
          className="terminal-cwd"
          data-testid="terminal-cwd"
          title={cwd ?? initialCwd ?? undefined}
        >
          {cwd ?? initialCwd ?? "当前目录"}
        </span>
        <span className="terminal-header-spacer" />
        {running && (
          <span className="terminal-running" data-testid="terminal-running">
            <Spin size="small" /> 运行中
          </span>
        )}
        <Tooltip title="清空终端">
          <Button
            type="text"
            size="small"
            className="terminal-icon-button"
            icon={<ClearOutlined />}
            aria-label="清空终端"
            data-testid="terminal-clear"
            onClick={clearLines}
          />
        </Tooltip>
        <Tooltip title="关闭终端">
          <Button
            type="text"
            size="small"
            className="terminal-icon-button"
            icon={<CloseOutlined />}
            aria-label="关闭终端"
            data-testid="terminal-close"
            onClick={onClose}
          />
        </Tooltip>
      </header>

      <div
        className="terminal-output"
        data-testid="terminal-output"
        ref={outputRef}
        onClick={() => inputRef.current?.focus()}
      >
        {lines.length === 0 ? (
          <div className="terminal-empty">
            在服务器上执行命令，按 Enter 运行；↑ / ↓ 浏览历史；Tab 补全路径。
          </div>
        ) : (
          lines.map((line) => (
            <div
              key={line.id}
              className={`terminal-line terminal-line-${line.kind}`}
              data-testid="terminal-line"
              data-kind={line.kind}
            >
              {line.text}
            </div>
          ))
        )}
        {/* 内联 prompt：输入直接位于输出内容末尾，像真实终端（task-35）。 */}
        <div
          className={`terminal-inline-input${running ? " is-disabled" : ""}`}
          data-testid="terminal-inline-row"
        >
          <Text className="terminal-prompt">$</Text>
          <Input
            ref={inputRef}
            variant="borderless"
            className="terminal-input"
            data-testid="terminal-input"
            placeholder="输入命令…"
            value={input}
            disabled={running}
            spellCheck={false}
            autoComplete="off"
            onChange={(event) => {
              // 手动编辑后退出历史浏览，↑ 重新从最近一条命令开始。
              historyIndexRef.current = -1;
              draftRef.current = "";
              setInput(event.target.value);
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void runCommand();
              } else if (event.key === "Tab") {
                event.preventDefault();
                void completePath();
              } else if (event.key === "ArrowUp") {
                event.preventDefault();
                navigateHistory(-1);
              } else if (event.key === "ArrowDown") {
                event.preventDefault();
                navigateHistory(1);
              } else if (
                (event.metaKey || event.ctrlKey) &&
                event.key.toLowerCase() === "l"
              ) {
                event.preventDefault();
                clearLines();
              }
            }}
          />
        </div>
      </div>
    </section>
  );
}
