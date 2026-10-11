package com.wenshan.struts2.action;

/**
 * Struts2 最基础的 Action 示例。
 * <p>
 * Struts2 Action 不继承任何父类（POJO），
 * 通过 execute() 方法处理请求，返回逻辑视图名，
 * 再由 struts.xml 中的 <result> 映射到真实页面。
 */
public class HelloAction {

    private String message;

    public String execute() {
        this.message = "这是你的第一个 Struts2 Action！底层通过 Filter 拦截请求，"
                + "经拦截器链处理后调用 execute()。";
        System.out.println("[Struts2] HelloAction.execute() 被调用");
        return "success";
    }

    // Struts2 通过 getter/setter 自动将属性暴露给 JSP
    public String getMessage() {
        return message;
    }
}
