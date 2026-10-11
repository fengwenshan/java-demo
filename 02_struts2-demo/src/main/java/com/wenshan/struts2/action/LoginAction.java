package com.wenshan.struts2.action;

/**
 * 演示 Struts2 处理表单提交（模型驱动）。
 * <p>
 * 访问 /login → 显示表单，提交后返回欢迎页。
 */
public class LoginAction {

    private String username;
    private String password;

    /**
     * 处理登录请求。
     * 如果用户名/密码为空 → 返回 input（回表单页）
     * 否则 → 返回 success
     */
    public String execute() {
        System.out.println("[Struts2] LoginAction — 用户名: " + username);
        if (username == null || username.isBlank()) {
            return "input";
        }
        return "success";
    }

    // --- getter / setter ---

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
