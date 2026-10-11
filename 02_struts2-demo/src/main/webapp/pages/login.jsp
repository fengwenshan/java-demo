<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><title>登录 — Struts2</title></head>
<body>
    <h1>Struts2 表单处理演示</h1>
    <form action="login.action" method="post">
        <label>用户名：<input type="text" name="username"></label><br><br>
        <label>密　码：<input type="password" name="password"></label><br><br>
        <input type="submit" value="登录">
    </form>
</body>
</html>
