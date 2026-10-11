package com.wenshan.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类。
 * <p>
 * @SpringBootApplication 一个注解组合了三件事：
 * <ul>
 *   <li>@Configuration  — 标记配置类</li>
 *   <li>@EnableAutoConfiguration — 自动配置（根据 classpath 自动装配）</li>
 *   <li>@ComponentScan — 扫描本包及子包的组件</li>
 * </ul>
 * <p>
 * 对比 Spring MVC：不需要 web.xml、不需要 spring-mvc.xml、不需要手动装 Tomcat，
 * 一个 main 方法全搞定。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
