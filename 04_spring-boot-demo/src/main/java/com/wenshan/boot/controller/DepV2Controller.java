package com.wenshan.boot.controller;

import com.wenshan.boot.common.Result;
import com.wenshan.boot.pojo.Department;
import com.wenshan.boot.service.DepV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口（MyBatis 版，操作 MySQL 的 dep 表）
 * <p>
 * 调用链：DepV2Controller -> DepV2Service -> DepV2Mapper -> dep 表
 * 与旧 demo 的 DepController（读 dep.txt）互不影响。
 */
@RestController
@RequestMapping("/dep-v2")
public class DepV2Controller {

    @Autowired
    private DepV2Service depV2Service;

    /** 查询全部部门（按 sort 升序） */
    @GetMapping
    public Result<List<Department>> list() {
        return Result.success(depV2Service.listAll());
    }

    /** 按 id 查询单个部门 */
    @GetMapping("/{id}")
    public Result<Department> get(@PathVariable Integer id) {
        return Result.success(depV2Service.getById(id));
    }

    /** 新增部门，自增 id 回填到 department */
    @PostMapping
    public Result<Department> add(@RequestBody Department department) {
        depV2Service.add(department);
        return Result.success(department);
    }

    /** 修改部门 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Integer id, @RequestBody Department department) {
        department.setId(id);
        depV2Service.update(department);
        return Result.success();
    }

    /** 删除部门 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        depV2Service.delete(id);
        return Result.success();
    }
}
