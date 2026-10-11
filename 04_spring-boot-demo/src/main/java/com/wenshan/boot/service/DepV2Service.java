package com.wenshan.boot.service;

import com.wenshan.boot.pojo.Department;

import java.util.List;

/**
 * 部门业务层接口（数据库版，操作 MySQL 的 dep 表）
 */
public interface DepV2Service {

    List<Department> listAll();

    Department getById(Integer id);

    void add(Department department);

    void update(Department department);

    void delete(Integer id);
}
