package com.wenshan.boot.pojo;

import java.time.LocalDateTime;

/**
 * 部门实体类，对应 dep.txt 中的一行：id,dep,update
 * 它不依赖 Spring、不依赖 JPA、不依赖任何框架，纯纯的 JavaBean。所以放 pojo 包，名字和内容一致。
 */
public class Dep {
    private Integer id;
    private String dep;
    private LocalDateTime update;

    public Dep() {
    }

    public Dep(Integer id, String dep, LocalDateTime update) {
        this.id = id;
        this.dep = dep;
        this.update = update;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getDep() {
        return dep;
    }

    public void setDep(String dep) {
        this.dep = dep;
    }

    public LocalDateTime getUpdate() {
        return update;
    }

    public void setUpdate(LocalDateTime update) {
        this.update = update;
    }

    @Override
    public String toString() {
        return "Dep{id=" + id + ", dep='" + dep + "', update='" + update + "'}";
    }
}
