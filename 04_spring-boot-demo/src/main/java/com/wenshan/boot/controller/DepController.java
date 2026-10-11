package com.wenshan.boot.controller;

import com.wenshan.boot.common.Result;
import com.wenshan.boot.pojo.Dep;
import com.wenshan.boot.service.DepService;
import com.wenshan.boot.service.impl.DepServiceImpl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求处理类
 * @ResponseBody
 *  - 将方法返回值直接响应给客户端，如果返回值类型是实体对象/集合，将会转换成json对象
 *  - 位置：类上 或者 Controller 方法上
 *  - @RestController = @ResponseBody + @Controller
 *  - 一般直接在请求雷上加@RestController，就不需要加@ResponseBody注解
 */
@RestController
// @RequestMapping("/dep")
public class DepController {

    /**
     * 查询所有部门
     */
    @GetMapping("/dep")
    public Result<List<Dep>> getDeps() throws IOException {
        // 从 classpath（src/main/resources）加载 dep.txt
        File file = new ClassPathResource("dep.txt").getFile();
        String content = FileUtils.readFileToString(file, StandardCharsets.UTF_8);

        // 按换行切割：\r?\n 能兼容 Windows(\r\n) 和 Linux/Mac(\n) 换行符
        String[] lines = content.split("\\r?\\n");

        // 解析每行并封装成 Dep 对象列表
        List<Dep> depList = new ArrayList<>();
        for (String line : lines) {
            // 跳过空行
            if (line.trim().isEmpty()) {
                continue;
            }
            // 按逗号切割成 id, dep, update
            String[] fields = line.split(",");
            Integer id = Integer.valueOf(fields[0].trim());
            String dep = fields[1].trim();
            LocalDateTime update = LocalDateTime.parse(
                    fields[2].trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            depList.add(new Dep(id, dep, update));
        }

        // 用统一 Result 封装后返回，Spring 会自动转成 JSON
        return Result.success(depList);
    }

    @GetMapping("/depV2")
    public Result<List<Dep>> getDepsV2() throws IOException {
        // ...
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("dep.txt");
        List<String> lines = IOUtils.readLines(in, StandardCharsets.UTF_8);
        List<Dep> depList = lines.stream().map(line -> {
            String[] parts = line.split(",");
            Integer id = Integer.parseInt(parts[0].trim());
            String dep = parts[1].trim();
            LocalDateTime update = LocalDateTime.parse(
                    parts[2].trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return new Dep(id, dep, update);   // lambda 必须返回 Dep 对象
        }).toList();
        return Result.success(depList);
    }

    /** 这里使用多态类型转换。如果后面换成了DepServiceImplV2,改一下new就行了 */
    private  final DepService _depService = new DepServiceImpl();
    @GetMapping("/depV3")
    public Result<List<Dep>> getDepsV3() throws IOException {
        /*
            解构3层：
                1.数据访问 Dao 数据访问（Data Access Object）持久层，负责数据的操作。
                2.逻辑处理 Service、
                3.接收请求，响应数据 Controller
            然后还添加了抽象层
         */
        return Result.success(_depService.queryDeps());
    }



    @Autowired
    private DepService depService;
    @GetMapping("/depV4")
    public Result<List<Dep>> getDepsV4() throws IOException {
        return Result.success(depService.queryDeps());
    }
}


