package com.wenshan.boot.dao;
import org.apache.commons.io.IOUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class DepDaoImpl  implements DepDao {
    @Override
    public List<String> queryDeps () {
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("dep.txt");
        return IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
    }
}




