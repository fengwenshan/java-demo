package com.wenshan.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;

/**
 * 演示如何从 HttpServletRequest 中获取各种请求信息。
 * <p>
 * 访问示例: /request-info?name=张三&city=北京
 */
public class RequestInfoServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        out.println("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>请求信息</title></head>
                <body>
                    <h1>HttpServletRequest 请求信息</h1>
                """);

        // 基本信息
        out.printf("<h3>基本信息</h3><ul>");
        out.printf("<li><b>请求方法:</b> %s</li>", req.getMethod());
        out.printf("<li><b>请求 URL:</b> %s</li>", req.getRequestURL());
        out.printf("<li><b>请求 URI:</b> %s</li>", req.getRequestURI());
        out.printf("<li><b>查询字符串:</b> %s</li>", req.getQueryString());
        out.printf("<li><b>协议:</b> %s</li>", req.getProtocol());
        out.printf("</ul>");

        // 请求参数
        out.printf("<h3>请求参数</h3><ul>");
        Map<String, String[]> params = req.getParameterMap();
        if (params.isEmpty()) {
            out.println("<li>无请求参数（试试在 URL 后加 ?name=张三&city=北京）</li>");
        } else {
            for (Map.Entry<String, String[]> entry : params.entrySet()) {
                out.printf("<li><b>%s:</b> %s</li>",
                        entry.getKey(), String.join(", ", entry.getValue()));
            }
        }
        out.printf("</ul>");

        // 请求头
        out.printf("<h3>请求头</h3><ul>");
        Collections.list(req.getHeaderNames()).stream()
                .sorted()
                .forEach(name -> out.printf("<li><b>%s:</b> %s</li>", name, req.getHeader(name)));
        out.printf("</ul>");

        // 客户端信息
        out.printf("<h3>客户端信息</h3><ul>");
        out.printf("<li><b>客户端 IP:</b> %s</li>", req.getRemoteAddr());
        out.printf("<li><b>客户端端口:</b> %s</li>", req.getRemotePort());
        out.printf("<li><b>服务器名:</b> %s</li>", req.getServerName());
        out.printf("<li><b>服务器端口:</b> %s</li>", req.getServerPort());
        out.printf("</ul>");

        out.println("<p><a href=\"/hello\">返回首页</a></p>");
        out.println("</body></html>");
    }
}
