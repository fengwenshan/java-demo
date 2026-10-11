<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><title>Hello ${name}</title></head>
<body>
    <h1>Hello, ${name}！</h1>
    <p>服务端时间：${time}</p>
    <p>这里你看到的是 ViewResolver 把 "hello" 拼接成 /WEB-INF/views/hello.jsp 的结果。</p>
    <p><a href="./">返回首页</a></p>
</body>
</html>
