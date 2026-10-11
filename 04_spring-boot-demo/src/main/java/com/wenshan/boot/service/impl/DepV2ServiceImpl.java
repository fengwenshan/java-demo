package com.wenshan.boot.service.impl;

import com.wenshan.boot.mapper.DepV2Mapper;
import com.wenshan.boot.pojo.Department;
import com.wenshan.boot.service.DepV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部门业务层实现（数据库版，实现 DepV2Service 接口）
 * <p>
 * 调用链：DepV2Controller -> DepV2Service -> DepV2ServiceImpl -> DepV2Mapper -> MySQL 的 dep 表
 */
@Service
public class DepV2ServiceImpl implements DepV2Service {

    @Autowired
    private DepV2Mapper depV2Mapper;

    @Override
    public List<Department> listAll() {
        return depV2Mapper.listAll();
    }

    @Override
    public Department getById(Integer id) {
        return depV2Mapper.getById(id);
    }

    @Override
    public void add(Department department) {
        depV2Mapper.insert(department);
    }

    @Override
    public void update(Department department) {
        depV2Mapper.update(department);
    }

    @Override
    public void delete(Integer id) {
        depV2Mapper.deleteById(id);
    }
}
