package com.wenshan.spring.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

/**
 * Spring MVC 基础 Controller 示例。
 * <p>
 * 对比 Servlet/Struts2 时代，注意这里的变化：
 * <ul>
 *   <li>不继承任何父类，纯 POJO + 注解</li>
 *   <li>方法返回字符串就是视图名，由 ViewResolver 拼接</li>
 *   <li>方法参数由 Spring 自动注入（Model、@RequestParam 等）</li>
 * </ul>
 */
@Controller
public class HelloController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "Spring MVC") String name, Model model) {
        model.addAttribute("name", name);
        model.addAttribute("time", LocalDateTime.now());
        System.out.println("[Spring MVC] /hello 被访问，name=" + name);
        return "hello";
    }

    @GetMapping("/compare")
    public String compare(Model model) {
        model.addAttribute("title", "Servlet vs Struts2 vs Spring MVC");
        return "compare";
    }
}
