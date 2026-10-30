import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { App as AntdApp, Input, Spin } from "antd";
import type { InputRef } from "antd";
import {
  FileOutlined,
  FolderOutlined,
  PictureOutlined,
  SearchOutlined,
} from "@ant-design/icons";

import { errorMessage, searchServerFiles } from "../api";
import type { SearchEntry, SearchResult } from "../types";
import { fileExtension, formatBytes } from "../utils";

/** 快速查找防抖时间（ms）。 */
const QUICK_OPEN_DEBOUNCE_MS = 280;
/** 结果上限（与服务端 search limit 对齐）。 */
const QUICK_OPEN_LIMIT = 200;

const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "webp", "svg", "bmp"]);

export interface QuickOpenProps {
  open: boolean;
  /** 当前存储根 id；null 表示旧单根服务端的默认根。 */
  rootId: string | null;
  onClose: () => void;
  /** 打开某条结果：文件由父组件跳 parent+高亮，目录由父组件进入。 */
  onSelect: (entry: SearchEntry) => void;
}

/** 按文件类型给出 VSCode 风格图标（目录 / JS / VUE / 图片 / 通用）。 */
function typeIcon(entry: SearchEntry) {
  if (entry.type === "directory") {
    return <FolderOutlined style={{ color: "#e8b339" }} data-testid="quick-open-icon-dir" />;
  }
  const ext = fileExtension(entry.name);
  if (ext === "js" || ext === "ts" || ext === "jsx" || ext === "tsx") {
    return (
      <span className="qo-badge qo-badge-js" data-testid="quick-open-icon-js">
        {ext === "ts" || ext === "tsx" ? "TS" : "JS"}
      </span>
    );
  }
  if (ext === "vue") {
    return (
      <span className="qo-badge qo-badge-vue" data-testid="quick-open-icon-vue">
        V
      </span>
    );
  }
  if (IMAGE_EXTENSIONS.has(ext)) {
    return <PictureOutlined style={{ color: "#36b37e" }} data-testid="quick-open-icon-image" />;
  }
  return <FileOutlined style={{ color: "#8c8c8c" }} data-testid="quick-open-icon-file" />;
}

/** 名称中命中关键字的片段高亮。 */
function HighlightedName({ name, keyword }: { name: string; keyword: string }) {
  const trimmed = keyword.trim();
  if (!trimmed) return <>{name}</>;
  const lowerName = name.toLowerCase();
  const lowerKeyword = trimmed.toLowerCase();
  const index = lowerName.indexOf(lowerKeyword);
  if (index < 0) return <>{name}</>;
  return (
    <>
      {name.slice(0, index)}
      <mark className="quick-open-mark">{name.slice(index, index + trimmed.length)}</mark>
      {name.slice(index + trimmed.length)}
    </>
  );
}

/**
 * VSCode 式快速查找浮层（task-21）：输入关键字同时搜索文件与目录，
 * ↑↓ 选择、Enter 打开、Esc 关闭；请求带序号丢弃过期响应。
 */
export default function QuickOpen({ open, rootId, onClose, onSelect }: QuickOpenProps) {
  const { message } = AntdApp.useApp();
  const [keyword, setKeyword] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);

  const seqRef = useRef(0);
  const inputRef = useRef<InputRef | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  const entries = useMemo(() => result?.entries ?? [], [result]);
  const trimmedKeyword = keyword.trim();

  // 打开时记录焦点、清空上次内容并聚焦输入框。
  useEffect(() => {
    if (!open) return;
    restoreFocusRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    seqRef.current += 1;
    setKeyword("");
    setResult(null);
    setError(null);
    setLoading(false);
    setActiveIndex(0);
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30);
    return () => window.clearTimeout(timer);
  }, [open]);

  // 关闭后清空状态，并把焦点还给打开前的元素。
  useEffect(() => {
    if (open) return;
    seqRef.current += 1;
    setKeyword("");
    setResult(null);
    setError(null);
    setLoading(false);
    setActiveIndex(0);
    const focusTarget = restoreFocusRef.current;
    restoreFocusRef.current = null;
    if (focusTarget) focusTarget.focus({ preventScroll: true });
  }, [open]);

  // 输入防抖：280ms 后调用 search_server_files(keyword, 200, root)；
  // 序号用于丢弃过期响应（竞态）。
  useEffect(() => {
    if (!open) return;
    const trimmed = keyword.trim();
    if (!trimmed) {
      seqRef.current += 1;
      setResult(null);
      setError(null);
      setLoading(false);
      setActiveIndex(0);
      return;
    }
    const seq = ++seqRef.current;
    setLoading(true);
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const next = await searchServerFiles(trimmed, QUICK_OPEN_LIMIT, rootId);
          if (seq !== seqRef.current) return;
          setResult(next);
          setError(null);
          setActiveIndex(0);
        } catch (caught) {
          if (seq !== seqRef.current) return;
          const text = errorMessage(caught);
          setError(text);
          setResult(null);
          message.error(text);
        } finally {
          if (seq === seqRef.current) setLoading(false);
        }
      })();
    }, QUICK_OPEN_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [keyword, message, open, rootId]);

  // 选中行滚动可见。
  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const active = list.querySelector<HTMLElement>('[data-active="true"]');
    active?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  const openEntry = useCallback(
    (entry: SearchEntry) => {
      onSelect(entry);
    },
    [onSelect],
  );

  const moveActive = useCallback(
    (delta: number) => {
      if (entries.length === 0) return;
      setActiveIndex((current) => (current + delta + entries.length) % entries.length);
    },
    [entries.length],
  );

  const handleInputKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLInputElement>) => {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        moveActive(1);
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        moveActive(-1);
        return;
      }
      if (event.key === "Enter") {
        event.preventDefault();
        const entry = entries[activeIndex];
        if (entry) openEntry(entry);
      }
    },
    [activeIndex, entries, moveActive, openEntry],
  );

  // Esc 关闭：浮层打开期间监听 window，输入框失焦时同样有效。
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, open]);

  if (!open) return null;

  const metaLabel = !trimmedKeyword
    ? "输入关键字开始搜索"
    : error
      ? "搜索失败"
      : loading
        ? "搜索中…"
        : `文件结果 ${entries.length}${result?.truncated ? " · 已截断" : ""}`;

  return (
    <div
      className="quick-open-mask"
      data-testid="quick-open-mask"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="quick-open" role="dialog" aria-label="查找文件" data-testid="quick-open">
        <div className="quick-open-input-wrap">
          <SearchOutlined className="quick-open-search-icon" />
          <Input
            ref={inputRef}
            variant="borderless"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onKeyDown={handleInputKeyDown}
            placeholder="搜索文件或文件夹名称…"
            data-testid="quick-open-input"
            aria-label="搜索文件或文件夹名称"
          />
        </div>
        <div className="quick-open-meta">
          <span data-testid="quick-open-count">{metaLabel}</span>
          {loading && <Spin size="small" />}
        </div>
        <div className="quick-open-list" ref={listRef} data-testid="quick-open-list" role="listbox">
          {!trimmedKeyword ? (
            <div className="quick-open-state" data-testid="quick-open-empty">
              输入关键字搜索当前存储目录
            </div>
          ) : error ? (
            <div className="quick-open-state quick-open-error" data-testid="quick-open-error">
              {error}
            </div>
          ) : loading && entries.length === 0 ? (
            <div className="quick-open-state" data-testid="quick-open-loading-state">
              <Spin size="small" /> 搜索中…
            </div>
          ) : entries.length === 0 && !loading ? (
            <div className="quick-open-state" data-testid="quick-open-not-found">
              未找到匹配的文件或目录
            </div>
          ) : (
            entries.map((entry, index) => (
              <div
                key={entry.path}
                className={`quick-open-item${index === activeIndex ? " is-active" : ""}`}
                data-testid="quick-open-item"
                data-path={entry.path}
                data-active={index === activeIndex ? "true" : "false"}
                role="option"
                aria-selected={index === activeIndex}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => openEntry(entry)}
              >
                <span className="quick-open-icon">{typeIcon(entry)}</span>
                <span className="quick-open-main">
                  <span className="quick-open-name">
                    <HighlightedName name={entry.name} keyword={keyword} />
                  </span>
                  <span className="quick-open-path" title={entry.path}>
                    {entry.path}
                  </span>
                </span>
                <span className="quick-open-type">
                  {entry.type === "directory" ? "目录" : formatBytes(entry.size)}
                </span>
              </div>
            ))
          )}
        </div>
        <div className="quick-open-footer">
          <span>
            <kbd className="quick-open-kbd">↑↓</kbd> 选择
          </span>
          <span>
            <kbd className="quick-open-kbd">Enter</kbd> 打开
          </span>
          <span>
            <kbd className="quick-open-kbd">Esc</kbd> 关闭
          </span>
          <span className="quick-open-footer-spacer" />
          <span>同时搜索文件与文件夹</span>
        </div>
      </div>
    </div>
  );
}
