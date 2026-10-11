package com.wenshan.boot.dao;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 当前类全部注入
// @Component
@Repository
public class DepDaoV2Impl implements DepDao {

    @Override
    public List<String> queryDeps () {
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("dep.txt");
        return IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
    }
}
