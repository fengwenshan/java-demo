package com.wenshan.boot.pojo;

import java.time.LocalDateTime;

/**
 * 部门实体类，对应数据库 dep 表：id, dep_name, leader, sort, create_time, update_time
 * <p>
 * 字段名与表列名用驼峰/下划线映射（需开启 map-underscore-to-camel-case）。
 * 和旧 demo 的 Dep（对应 dep.txt）是两个不同的实体，互不影响。
 */
public class Department {
    private Integer id;
    private String depName;
    private String leader;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getDepName() {
        return depName;
    }

    public void setDepName(String depName) {
        this.depName = depName;
    }

    public String getLeader() {
        return leader;
    }

    public void setLeader(String leader) {
        this.leader = leader;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "Department{id=" + id + ", depName='" + depName + "', leader='" + leader
                + "', sort=" + sort + ", createTime=" + createTime + ", updateTime=" + updateTime + "}";
    }
}
