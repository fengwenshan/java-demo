# servlet开发




## 开发

- 定义一个类，实现servlet接口（继承HttpServlet）,并实现所有方法
- 在Servlet类上使用@WebServlet注解，配置该Servlet的访问路径

```java
// http://localhost:8080/hello?name=xxx
@webServlet(urlPatterns = "/hello")
// @webServlet("/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取请求参数
        String name = req.getParameter("name");
        String respMsg = "<h1>Hello, " + name + "~</h1>";
        req.setCharacterEncoding("utf-8");
        // 响应结果
        resp.getWriter().write(respMsg);
    }
}
```



