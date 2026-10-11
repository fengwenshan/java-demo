package com.wenshan.boot.mapper;

import com.wenshan.boot.pojo.Department;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 部门表 Mapper（MyBatis）。
 * <p>
 * @Mapper 让 MyBatis 启动时扫描并生成代理实现，注入到 Service 层。
 * 注解里直接写 SQL，表名/列名对应 MySQL 的 dep 表。
 */
@Mapper
public interface DepV2Mapper {

    @Select("SELECT id, dep_name, leader, sort, create_time, update_time FROM dep ORDER BY sort ASC, id ASC")
    List<Department> listAll();

    @Select("SELECT id, dep_name, leader, sort, create_time, update_time FROM dep WHERE id = #{id}")
    Department getById(Integer id);

    @Insert("INSERT INTO dep(dep_name, leader, sort) VALUES(#{depName}, #{leader}, #{sort})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Department department);

    @Update("UPDATE dep SET dep_name = #{depName}, leader = #{leader}, sort = #{sort} WHERE id = #{id}")
    void update(Department department);

    @Delete("DELETE FROM dep WHERE id = #{id}")
    void deleteById(Integer id);
}
