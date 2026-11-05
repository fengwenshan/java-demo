import React from "react";
import ReactDOM from "react-dom/client";
import { App as AntdApp, ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";

import RootApp from "./App";
import "./styles.css";

// 开发版叫 ScopeFTP-dev、安装版叫 ScopeFTP：开发构建改窗口标题，配合标题栏上的 Dev 标签区分。
if (import.meta.env.DEV) {
  document.title = "ScopeFTP-dev";
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: "#1677ff",
          borderRadius: 8,
          fontSize: 14,
        },
      }}
    >
      <AntdApp>
        <RootApp />
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
);
