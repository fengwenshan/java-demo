package com.wenshan.boot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot REST Controller。
 * <p>
 * 和 Spring MVC 的区别：
 * <ul>
 *   <li>@RestController = @Controller + @ResponseBody（每个方法返回 JSON，不用写 @ResponseBody）</li>
 *   <li>零 XML 配置，Spring Boot 自动扫描并注册</li>
 * </ul>
 */
@RestController
public class HelloController {

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "Spring Boot") String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Hello, " + name + "!");
        result.put("time", LocalDateTime.now().toString());
        result.put("note", "这就是 Spring Boot —— 零配置，一个 main 方法跑起来");
        return result;
    }

    @GetMapping("/evolution")
    public Map<String, String> evolution() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("01 Servlet", "手写 HttpServlet + web.xml 注册，最底层");
        map.put("02 Struts2", "Filter 入口 + struts.xml + 拦截器链，封装了一层");
        map.put("03 Spring MVC", "DispatcherServlet + @Controller 注解，IoC 容器管理一切");
        map.put("04 Spring Boot", "自动配置 + Starter + 内嵌容器，约定优于配置");
        return map;
    }
}
