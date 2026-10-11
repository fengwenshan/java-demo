package com.wenshan.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * 最基础的 Servlet 示例 —— 响应 "Hello Servlet!"
 */
public class HelloServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Hello Servlet</title></head>
                <body>
                    <h1>Hello Servlet!</h1>
                    <p>这是你的第一个 Jakarta Servlet 程序。</p>
                    <hr>
                    <ul>
                        <li><a href="/lifecycle">查看生命周期演示</a></li>
                        <li><a href="/request-info?name=张三&city=北京">查看请求信息</a></li>
                    </ul>
                </body>
                </html>
                """);
    }
}
