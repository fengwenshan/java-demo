package com.wenshan.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * 演示 Servlet 生命周期：init() → doGet() → destroy()
 * <p>
 * 运行后观察控制台日志输出，理解每个方法的调用时机。
 */
public class LifecycleServlet extends HttpServlet {

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        System.out.println("[Lifecycle] init() 被调用 —— " + LocalDateTime.now());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        System.out.println("[Lifecycle] doGet() 被调用 —— " + LocalDateTime.now());

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Servlet 生命周期</title></head>
                <body>
                    <h1>Servlet 生命周期</h1>
                    <table border="1" cellpadding="8" style="border-collapse:collapse">
                        <tr><th>阶段</th><th>说明</th></tr>
                        <tr><td>1. 加载 & 实例化</td><td>容器加载 Servlet 类并创建实例</td></tr>
                        <tr><td>2. init()</td><td>初始化，只调用一次（控制台已打印）</td></tr>
                        <tr><td>3. service() / doGet()</td><td>处理请求，每次请求都会调用（控制台已打印）</td></tr>
                        <tr><td>4. destroy()</td><td>销毁前调用，只调用一次（关闭服务器时触发）</td></tr>
                    </table>
                    <p><em>请查看控制台日志确认各阶段调用。</em></p>
                    <p><a href="/hello">返回首页</a></p>
                </body>
                </html>
                """);
    }

    @Override
    public void destroy() {
        System.out.println("[Lifecycle] destroy() 被调用 —— " + LocalDateTime.now());
        super.destroy();
    }
}
