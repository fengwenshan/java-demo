//! Monaco 本地打包配置（task-28）：不走 CDN，worker 由 Vite `?worker` 生成。
//!
//! Tauri 离线环境必须使用本地 monaco-editor；`loader.config({ monaco })` 让
//! `@monaco-editor/react` 复用同一实例，避免从 jsDelivr 动态加载。

import * as monaco from "monaco-editor";
import { loader } from "@monaco-editor/react";
import editorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import jsonWorker from "monaco-editor/esm/vs/language/json/json.worker?worker";
import cssWorker from "monaco-editor/esm/vs/language/css/css.worker?worker";
import htmlWorker from "monaco-editor/esm/vs/language/html/html.worker?worker";
import tsWorker from "monaco-editor/esm/vs/language/typescript/ts.worker?worker";

interface MonacoWorkerEnvironment {
  getWorker(workerId: string, label: string): Worker;
}

const monacoEnvironment: MonacoWorkerEnvironment = {
  getWorker(_workerId, label) {
    switch (label) {
      case "json":
        return new jsonWorker();
      case "css":
      case "scss":
      case "less":
        return new cssWorker();
      case "html":
      case "handlebars":
      case "razor":
        return new htmlWorker();
      case "typescript":
      case "javascript":
        return new tsWorker();
      default:
        return new editorWorker();
    }
  },
};

(globalThis as unknown as { MonacoEnvironment: MonacoWorkerEnvironment })
  .MonacoEnvironment = monacoEnvironment;

// 供 CDP/调试读取 model 语言；同时也是社区常用的全局挂载方式。
(globalThis as unknown as { monaco?: typeof monaco }).monaco = monaco;

loader.config({ monaco });
