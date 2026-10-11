<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><title>${title}</title>
<style>
    table { border-collapse: collapse; width: 100%; }
    th, td { border: 1px solid #ccc; padding: 10px; text-align: left; }
    th { background: #f5f5f5; }
</style>
</head>
<body>
    <h1>${title}</h1>
    <table>
        <tr>
            <th>维度</th><th>Servlet</th><th>Struts2</th><th>Spring MVC</th>
        </tr>
        <tr>
            <td>入口</td>
            <td>HttpServlet</td>
            <td>Filter（StrutsPrepareAndExecuteFilter）</td>
            <td>Servlet（DispatcherServlet）</td>
        </tr>
        <tr>
            <td>请求映射</td>
            <td>web.xml / @WebServlet</td>
            <td>struts.xml</td>
            <td>@RequestMapping / @GetMapping</td>
        </tr>
        <tr>
            <td>参数获取</td>
            <td>req.getParameter()</td>
            <td>Action 属性 + setter</td>
            <td>方法参数 + @RequestParam</td>
        </tr>
        <tr>
            <td>视图跳转</td>
            <td>req.getRequestDispatcher()</td>
            <td>struts.xml 中 &lt;result&gt;</td>
            <td>返回字符串 → ViewResolver</td>
        </tr>
        <tr>
            <td>核心思想</td>
            <td>手动作业</td>
            <td>拦截器链 + Action</td>
            <td>IoC 容器 + MVC 分离</td>
        </tr>
    </table>
    <p><a href="./">返回首页</a></p>
</body>
</html>
